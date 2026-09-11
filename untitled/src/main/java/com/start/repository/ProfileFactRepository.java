package com.start.repository;

import com.start.model.ProfileFactSummary;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/** Read-only access to confirmed, globally keyed personal profile facts. */
public class ProfileFactRepository implements Repository {
    private final DataSource dataSource;

    public ProfileFactRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public DataSource getDataSource() {
        return dataSource;
    }

    public List<ProfileFactSummary> findActiveByUserId(String userId, boolean privateContext, int limit)
            throws Exception {
        String scopeClause = privateContext
                ? "scope IN ('PERSONAL', 'PRIVATE_ONLY')"
                : "scope = 'PERSONAL'";
        String sql = "SELECT id, kind, epistemic_type, claim, stability, confidence, scope, " +
                "last_observed_at, valid_until FROM profile_facts " +
                "WHERE subject_user_id = ? AND status = 'ACTIVE' " +
                "AND (valid_until IS NULL OR valid_until > CURRENT_TIMESTAMP(3)) AND " + scopeClause + " " +
                "ORDER BY confidence DESC, last_observed_at DESC LIMIT ?";
        List<ProfileFactSummary> facts = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setInt(2, Math.max(1, Math.min(limit, 20)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp observed = rs.getTimestamp("last_observed_at");
                    Timestamp validUntil = rs.getTimestamp("valid_until");
                    facts.add(new ProfileFactSummary(
                            rs.getLong("id"), rs.getString("kind"), rs.getString("epistemic_type"),
                            rs.getString("claim"), rs.getString("stability"), rs.getDouble("confidence"),
                            rs.getString("scope"),
                            observed != null ? observed.toLocalDateTime() : null,
                            validUntil != null ? validUntil.toLocalDateTime() : null));
                }
            }
        }
        return List.copyOf(facts);
    }
}
