#!/usr/bin/env bash
# instalar-no-carro.sh — instala o Impulse Vidros e Travas no head unit via telnet.
#
# Uso:  ./tools/instalar-no-carro.sh <IP_DO_CARRO> [caminho-do-apk]
#
# O mesmo caminho do deploy do Impulse: o WiFi da central derruba transferencia longa,
# entao o APK vai em pedacos de 4MB por HTTP, e o telnet embaralha comando comprido,
# entao vai um comando curto por conexao.
#
# Diferente do Impulse, aqui nao ha preferencia para preservar nem exigencia de UID baixo:
# este aplicativo apenas PEDE autorizacao ao Shizuku, nao precisa inicia-lo.

set -uo pipefail

PKG="br.com.rafaelcs28.vidrostravas"
PORT=23
REMOTE_APK=/data/local/tmp/vidrostravas.apk
HTTP_PORT=8772

CAR_IP="${1:-}"
[ -z "$CAR_IP" ] && { echo "uso: $0 <IP_DO_CARRO> [apk]"; exit 1; }
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="${2:-$ROOT_DIR/app/build/outputs/apk/release/app-release.apk}"

log(){ echo "[vt] $*"; }
die(){ echo "[vt][ERRO] $*" >&2; exit 1; }

HU="$(mktemp /tmp/hu-vt.XXXXXX.py)"
cat > "$HU" <<'PY'
import socket,sys,time,re
host,port,cmd=sys.argv[1],int(sys.argv[2]),sys.argv[3]
timeout=float(sys.argv[4]) if len(sys.argv)>4 else 60.0
payload=('echo "__STA""RT__"; '+cmd+'; echo "__EN""D__"\r\n').encode('utf-8','replace')
try: s=socket.create_connection((host,port),timeout=8)
except OSError as e: sys.stderr.write("CONNECT ERROR: %s\n"%e); sys.exit(2)
s.settimeout(0.5)
t0=time.monotonic()
while time.monotonic()-t0<1.0:
    try:
        if not s.recv(4096): break
    except socket.timeout: break
for i in range(0,len(payload),48):
    s.sendall(payload[i:i+48]); time.sleep(0.03)
out=bytearray(); dl=time.monotonic()+timeout
while time.monotonic()<dl:
    try:
        d=s.recv(8192)
        if not d: break
        out+=d
        if b"__END__" in out: break
    except socket.timeout: continue
try: s.close()
except Exception: pass
raw=re.sub(rb'\xff[\x00-\xff]{2}',b'',bytes(out))
t=raw.decode('utf-8','replace').replace('\r','')
m=re.search(r'__START__\n(.*?)\n?__END__',t,re.S)
sys.stdout.write(m.group(1) if m else t)
PY

HTTP_PID=""
cleanup(){ rm -f "$HU" 2>/dev/null || true; [ -n "$HTTP_PID" ] && kill "$HTTP_PID" 2>/dev/null || true; }
trap cleanup EXIT
hu(){ python3 "$HU" "$CAR_IP" "$PORT" "$1" "${2:-60}"; }

[ -f "$APK" ] || die "APK nao encontrado: $APK"
APK_SIZE="$(wc -c < "$APK" | tr -dc '0-9')"

log "Testando $CAR_IP:$PORT ..."
ping -c1 -t2 "$CAR_IP" >/dev/null 2>&1 || die "Carro nao responde a ping ($CAR_IP)."
echo "$(hu 'id' 10)" | grep -q 'uid=0' || die "Telnet nao deu shell root em $CAR_IP."
log "Conectado (root). APK: $APK_SIZE bytes"

MAC_IP="$(route get "$CAR_IP" 2>/dev/null | awk '/interface:/{print $2}' | head -1 | xargs -I{} ipconfig getifaddr {} 2>/dev/null)"
[ -z "$MAC_IP" ] && MAC_IP="$(ipconfig getifaddr en0 2>/dev/null)"
[ -z "$MAC_IP" ] && die "Nao descobri o IP local do Mac."

CHUNK_DIR="$(dirname "$APK")/vt_chunks"
rm -rf "$CHUNK_DIR"; mkdir -p "$CHUNK_DIR"
( cd "$CHUNK_DIR" && split -b 4m "$APK" chunk_ )
N="$(ls "$CHUNK_DIR" | wc -l | tr -d ' ')"
python3 -m http.server "$HTTP_PORT" --bind 0.0.0.0 --directory "$CHUNK_DIR" >/tmp/vt_httpd.log 2>&1 &
HTTP_PID=$!; sleep 1
kill -0 "$HTTP_PID" 2>/dev/null || die "Servidor HTTP local nao subiu (porta $HTTP_PORT ocupada?)."

log "Enviando em $N pedaco(s) de $MAC_IP:$HTTP_PORT ..."
hu "rm -rf /data/local/tmp/vt_parts $REMOTE_APK; mkdir -p /data/local/tmp/vt_parts; echo ok" 20 >/dev/null
i=0
for c in "$CHUNK_DIR"/chunk_*; do
  i=$((i+1)); n="$(basename "$c")"; sz="$(wc -c < "$c" | tr -dc '0-9')"; ok=0
  for t in 1 2 3 4 5 6 7 8 9 10; do
    got="$(hu "curl -fsS --connect-timeout 5 http://$MAC_IP:$HTTP_PORT/$n -o /data/local/tmp/vt_parts/$n 2>/dev/null; wc -c < /data/local/tmp/vt_parts/$n 2>/dev/null" 60 | tr -dc '0-9')"
    [ "$got" = "$sz" ] && { ok=1; break; }
    log "  $n ($i/$N): tentativa $t falhou (${got:-0}/$sz)"; sleep 2
  done
  [ "$ok" = 1 ] || die "Falha enviando $n."
  log "  $n OK ($i/$N)"
done
hu "cat /data/local/tmp/vt_parts/chunk_* > $REMOTE_APK && rm -rf /data/local/tmp/vt_parts; echo ok" 60 >/dev/null
REMOTE_SIZE="$(hu "wc -c < $REMOTE_APK 2>/dev/null" 20 | tr -dc '0-9')"
kill "$HTTP_PID" 2>/dev/null || true; HTTP_PID=""
rm -rf "$CHUNK_DIR"
[ "$REMOTE_SIZE" = "$APK_SIZE" ] || die "Envio incompleto (remoto=$REMOTE_SIZE esperado=$APK_SIZE)."
LOCAL_MD5="$(md5 -q "$APK" 2>/dev/null || true)"
REMOTE_MD5="$(hu "md5sum $REMOTE_APK 2>/dev/null | cut -d' ' -f1" 30 | tr -dc 'a-f0-9')"
[ -n "$LOCAL_MD5" ] && [ -n "$REMOTE_MD5" ] && [ "$LOCAL_MD5" != "$REMOTE_MD5" ] && die "MD5 divergente."
log "APK no carro OK${REMOTE_MD5:+ (md5 confere)}."

log "Instalando..."
OUT="$(hu "pm install -r -d $REMOTE_APK 2>&1" 180)"
if echo "$OUT" | grep -qiE "beantechs disallow|INSTALL_FAILED"; then
  log "Bloqueio da OEM — injetando hook e repetindo..."
  hu 'cd /data/local/tmp || exit 1
pgrep fridaserver >/dev/null 2>&1 || { setsid ./fridaserver >/dev/null 2>&1 </dev/null & sleep 3; }
SP=$(pidof system_server)
setsid ./fridainject -p "$SP" -s system_server.js >/data/local/tmp/inject.log 2>&1 </dev/null &
sleep 5; echo hook_ok' 40 >/dev/null
  OUT="$(hu "pm install -r -d $REMOTE_APK 2>&1" 180)"
fi
echo "$OUT" | grep -qi Success || die "Install falhou: $OUT"

VER="$(hu "dumpsys package $PKG | grep versionName | head -1" 20)"
hu "am start -n $PKG/.MainActivity >/dev/null 2>&1; echo ok" 20 >/dev/null
log "Instalado e aberto. $VER"
