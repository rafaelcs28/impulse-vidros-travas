import java.util.regex.*;
public class TesteLinha {
  public static void main(String[] a) {
    Pattern p = Pattern.compile("^(\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d{3})\\s+(?:([A-Za-z0-9_]+):\\s*)?(\\d+)\\s+(\\d+)\\s+([VDIWEF])\\s+([^:]*?)\\s*: ?(.*)$");
    String[] linhas = {
      "09-23 15:27:41.378  6413  6413 W Its_IntelligentVehicleControlService: onDataChanged send package: br.com.rafaelcs28.vidrostravas, key: car.basic.vehicle_speed, value 62.4",
      "09-23 16:06:13.123 10052: 8307  8340 W ServiceManager: ControlService not initialized",
      "09-23 16:06:13.123 10052:12345 12346 E ServiceManager: Error closing all windows",
      "09-23 16:06:13.123  root:  123   123 W shizuku_server: Throwing OutOfMemoryError",
      "09-23 16:06:13.123 10052: 8307  8340 W Tag     : msg com espacos",
      "09-23 16:54:56.992  1000:  520  1203 I ActivityManager: Process br.com.redesurftank.havalshisuku (pid 8307) has died: fore SVC",
      "--------- beginning of main"
    };
    for (String l : linhas) {
      Matcher m = p.matcher(l);
      if (!m.find()) { System.out.println("SEM MATCH  | " + l.substring(0, Math.min(40, l.length()))); continue; }
      System.out.printf("uid=%-6s pid=%-6s tid=%-6s nivel=%s tag=[%s] msg=[%s]%n",
        m.group(2), m.group(3), m.group(4), m.group(5), m.group(6), m.group(7).length() > 38 ? m.group(7).substring(0, 38) + "..." : m.group(7));
    }
  }
}
