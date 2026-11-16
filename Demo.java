import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Creates one demo sample with its three steps. Run after loading schema.sql. */
public class Demo {
    public static void main(String[] args) throws Exception {
        String url  = env("DB_URL",  "jdbc:mysql://localhost:3306/microwatch");
        String user = env("DB_USER", "root");
        String pass = env("DB_PASS", "");

        try (Connection c = DriverManager.getConnection(url, user, pass)) {
            c.setAutoCommit(false);

            // 1) make sure a site exists
            int siteId;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT IGNORE INTO sites(code,name,latitude,longitude,position) VALUES('S03','Demo Site 03',18.2500,120.6000,'downstream')")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT site_id FROM sites WHERE code='S03'");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                siteId = rs.getInt(1);
            }

            // 2) next sequence number for this site and day
            LocalDate today = LocalDate.now();
            String prefix = SampleId.prefix("S03", today);
            int seq;
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM samples WHERE sample_id LIKE ?")) {
                ps.setString(1, prefix + "%");
                try (ResultSet rs = ps.executeQuery()) { rs.next(); seq = rs.getInt(1) + 1; }
            }
            String id = SampleId.create("S03", today, seq);

            // 3) insert sample + its 3 steps (same ID everywhere)
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO samples(sample_id,site_id,water_source,volume_ml,collected_at) VALUES(?,?,?,?,?)")) {
                ps.setString(1, id);
                ps.setInt(2, siteId);
                ps.setString(3, "River");
                ps.setInt(4, 1000);
                ps.setTimestamp(5, Timestamp.valueOf(LocalDateTime.now()));
                ps.executeUpdate();
            }
            String[] steps = {"Collection", "Filtration", "Imaging"};
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO sample_steps(sample_id,step_no,step_name) VALUES(?,?,?)")) {
                for (int i = 0; i < steps.length; i++) {
                    ps.setString(1, id);
                    ps.setInt(2, i + 1);
                    ps.setString(3, steps[i]);
                    ps.executeUpdate();
                }
            }
            c.commit();
            System.out.println("Created sample: " + id);
        }
    }

    private static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null ? d : v;
    }
}
