import java.util.regex.*;
public class TesteLinha2 {
  public static void main(String[] a) {
    String pat = "^(\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d{3})\\s+(?:([A-Za-z0-9_]+)(?::\\s*|\\s+))?(\\d+)\\s+(\\d+)\\s+([VDIWEF])\\s+([^:]*?)\\s*: ?(.*)$";
    Pattern p = Pattern.compile(pat);
    String[][] casos = {
      // {linha, uid esperado, pid esperado, tag esperada}
      {"09-24 09:37:53.101  1000  1852  2033 E WifiService: Failed to start scan", "1000","1852","WifiService"},                        // REAL deste carro
      {"09-24 09:37:53.101  1000  6337  6337 W Its_IntelligentVehicleControlService: onDataChanged send package: br", "1000","6337","Its_IntelligentVehicleControlService"}, // REAL
      {"09-24 09:37:53.101 10052  3244  3301 W ServiceManager: ControlService not initialized", "10052","3244","ServiceManager"},     // Impulse neste formato
      {"09-23 15:27:41.378  6413  6413 W Its_IntelligentVehicleControlService: onDataChanged", "","6413","Its_IntelligentVehicleControlService"}, // REAL sem uid - a armadilha
      {"09-23 16:06:13.123 10052:12345 12346 E ServiceManager: Error closing all windows", "10052","12345","ServiceManager"},       // AOSP grudado
      {"09-23 16:06:13.123 10052: 8307  8340 W ServiceManager: x", "10052","8307","ServiceManager"},                                 // AOSP com espaco
      {"09-23 16:06:13.123  root:  123   123 W shizuku_server: Throwing OutOfMemoryError", "root","123","shizuku_server"},
      {"09-23 16:06:13.123  root   123   123 W shizuku_server: y", "root","123","shizuku_server"},
    };
    int ok = 0;
    for (String[] c : casos) {
      Matcher m = p.matcher(c[0]);
      if (!m.find()) { System.out.println("FALHOU (sem match): " + c[0].substring(0, 45)); continue; }
      String uid = m.group(2) == null ? "" : m.group(2);
      boolean certo = uid.equals(c[1]) && m.group(3).equals(c[2]) && m.group(6).trim().equals(c[3]);
      if (certo) ok++;
      System.out.printf("%s uid=%-6s pid=%-6s tag=%s%n", certo ? "ok   " : "ERRO ", uid, m.group(3), m.group(6).trim());
    }
    System.out.println(ok + " de " + casos.length + " corretos");
  }
}
