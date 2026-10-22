import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Smoke-test residue cleaner (run by script/run_smoke.ps1).
 *
 * <p>Removes data created by verify.ps1 / smoke_family.ps1:
 * mock users (vo_/vm2_/vm3_/vm4_/smk_) and families named verify_* / smokeX_*,
 * together with their child rows (guards against a smoke run that aborted
 * before its dissolve step).</p>
 *
 * <p>Usage: SmokeCleanup &lt;host&gt; &lt;port&gt; &lt;user&gt; &lt;password&gt; &lt;db&gt;</p>
 *
 * <p>Safety: refuses to run unless the target schema is a dev/test schema.</p>
 */
public class SmokeCleanup {

    /** Only dev/test schemas may be touched; liunian_account is production. */
    private static final java.util.Set<String> ALLOWED_DBS =
            java.util.Set.of("liunian_account_dev", "liunian_account_test");
    private static final String FAMILY_PATTERN = "^(verify2?_|smoke[A-Z]_)";
    private static final String USER_PATTERN = "^(vo_|vm[234]_|smk_)";

    public static void main(String[] args) {
        if (args.length < 5) {
            System.out.println("[CLEAN] usage: <host> <port> <user> <password> <db>");
            System.exit(2);
        }
        String host = args[0];
        String port = args[1];
        String user = args[2];
        String password = args[3];
        String db = args[4];

        if (!ALLOWED_DBS.contains(db)) {
            System.out.println("[CLEAN] refused: db=" + db + " is not a dev/test schema " + ALLOWED_DBS);
            System.exit(2);
        }

        String url = "jdbc:mysql://" + host + ":" + port + "/" + db
                + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai";

        int total = 0;
        try (Connection c = DriverManager.getConnection(url, user, password)) {
            String actual = first(c, "SELECT DATABASE()");
            if (!ALLOWED_DBS.contains(actual)) {
                System.out.println("[CLEAN] refused: connected schema is " + actual);
                System.exit(2);
            }

            c.setAutoCommit(false);
            try {
                // families created by a smoke run (normally already dissolved)
                String ids = ids(c, "SELECT id FROM `family` WHERE name REGEXP '" + FAMILY_PATTERN + "'");
                if (!ids.isEmpty()) {
                    total += update(c, "DELETE FROM `transaction` WHERE family_id IN (" + ids + ")");
                    total += update(c, "DELETE FROM `saving_record` WHERE family_id IN (" + ids + ")");
                    total += update(c, "DELETE FROM `saving_account` WHERE family_id IN (" + ids + ")");
                    total += update(c, "DELETE FROM `join_request` WHERE family_id IN (" + ids + ")");
                    total += update(c, "DELETE FROM `invitation` WHERE family_id IN (" + ids + ")");
                    total += update(c, "DELETE FROM `reminder_setting` WHERE family_id IN (" + ids + ")");
                    total += update(c, "DELETE FROM `budget` WHERE family_id IN (" + ids + ")");
                    total += update(c, "DELETE FROM `family_member` WHERE family_id IN (" + ids + ")");
                    total += update(c, "DELETE FROM `category` WHERE family_id IN (" + ids + ")");
                    total += update(c, "DELETE FROM `family` WHERE id IN (" + ids + ")");
                }

                // mock accounts created by login during smoke runs
                total += update(c, "DELETE FROM `user` WHERE openid REGEXP '" + USER_PATTERN + "'");

                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }

            System.out.println("[CLEAN] removed rows=" + total
                    + " (families left=" + first(c, "SELECT COUNT(*) FROM `family`")
                    + ", smoke users left=" + first(c, "SELECT COUNT(*) FROM `user` WHERE openid REGEXP '" + USER_PATTERN + "'") + ")");
        } catch (SQLException e) {
            System.out.println("[CLEAN] failed: " + e.getMessage());
            System.exit(1);
        }
    }

    private static String first(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next();
            return String.valueOf(r.getObject(1));
        }
    }

    private static String ids(Connection c, String sql) throws SQLException {
        StringBuilder sb = new StringBuilder();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            while (r.next()) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(r.getLong(1));
            }
        }
        return sb.toString();
    }

    private static int update(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            return s.executeUpdate(sql);
        } catch (SQLException e) {
            System.out.println("[CLEAN] sql failed: " + sql);
            throw e;
        }
    }
}
