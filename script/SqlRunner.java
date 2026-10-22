import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Minimal SQL file runner (used by script/apply_sql.ps1).
 *
 * <p>Executes every statement of a .sql file (statements separated by ';',
 * lines starting with '--' are stripped). No mysql client is required.</p>
 *
 * <p>Usage: SqlRunner &lt;host&gt; &lt;port&gt; &lt;user&gt; &lt;password&gt; &lt;db&gt; &lt;sqlFile&gt;</p>
 *
 * <p>Safety: refuses to run unless the target schema is a dev/test schema.</p>
 */
public class SqlRunner {

    /** Only dev/test schemas may be touched; liunian_account is production. */
    private static final java.util.Set<String> ALLOWED_DBS =
            java.util.Set.of("liunian_account_dev", "liunian_account_test");

    public static void main(String[] args) throws Exception {
        if (args.length < 6) {
            System.out.println("[SQL] usage: <host> <port> <user> <password> <db> <sqlFile>");
            System.exit(2);
        }
        String host = args[0];
        String port = args[1];
        String user = args[2];
        String password = args[3];
        String db = args[4];
        Path file = Path.of(args[5]);

        if (!ALLOWED_DBS.contains(db)) {
            System.out.println("[SQL] refused: db=" + db + " is not a dev/test schema " + ALLOWED_DBS);
            System.exit(2);
        }
        if (!Files.exists(file)) {
            System.out.println("[SQL] file not found: " + file);
            System.exit(2);
        }

        String url = "jdbc:mysql://" + host + ":" + port + "/" + db
                + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8"
                + "&connectionTimeZone=%2B08:00&forceConnectionTimeZoneToSession=true";

        String raw = Files.readString(file, StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (t.startsWith("--")) {
                continue;
            }
            sb.append(line).append('\n');
        }

        int ok = 0;
        try (Connection c = DriverManager.getConnection(url, user, password); Statement s = c.createStatement()) {
            String actual = null;
            try (ResultSet r = s.executeQuery("SELECT DATABASE()")) {
                if (r.next()) {
                    actual = r.getString(1);
                }
            }
            if (!ALLOWED_DBS.contains(actual)) {
                System.out.println("[SQL] refused: connected schema is " + actual);
                System.exit(2);
            }
            for (String part : sb.toString().split(";")) {
                String stmt = part.trim();
                if (stmt.isEmpty()) {
                    continue;
                }
                try {
                    if (stmt.toLowerCase().startsWith("select")) {
                        // SELECT 语句打印结果（用于人工核对数据，最多 200 行）
                        try (ResultSet rs = s.executeQuery(stmt)) {
                            java.sql.ResultSetMetaData md = rs.getMetaData();
                            int cols = md.getColumnCount();
                            int rows = 0;
                            while (rs.next() && rows < 200) {
                                StringBuilder line = new StringBuilder("[SQL] row=");
                                for (int i = 1; i <= cols; i++) {
                                    if (i > 1) {
                                        line.append(", ");
                                    }
                                    line.append(md.getColumnLabel(i)).append('=').append(rs.getString(i));
                                }
                                System.out.println(line);
                                rows++;
                            }
                            System.out.println("[SQL] " + rows + " row(s)");
                        }
                    } else {
                        s.execute(stmt);
                    }
                    ok++;
                } catch (SQLException e) {
                    System.out.println("[SQL] FAILED: " + firstLine(stmt));
                    System.out.println("[SQL] " + e.getMessage());
                    System.exit(1);
                }
            }
            System.out.println("[SQL] applied " + ok + " statement(s) from " + file.getFileName()
                    + " to " + actual);
        }
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        String head = i > 0 ? s.substring(0, i) : s;
        return head.length() > 120 ? head.substring(0, 120) + "..." : head;
    }
}
