package com.start.repository;

import com.start.config.DatabaseConfig;
import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;


/**
 * 数据库操作抽象类
 */
public abstract class BaseRepository implements Repository {

    @Override
    public DataSource getDataSource() {
        return DatabaseConfig.getDataSource();
    }

    /**
     * 安全的数据库操作包装器
     */
    protected <T> DatabaseResult<T> safeExecute(DatabaseOperation<T> operation) {
        try {
            T result = operation.execute();
            return DatabaseResult.success(result);
        } catch (SQLException e) {
            System.err.println("数据库操作失败: " + e.getMessage());
            e.printStackTrace();
            return DatabaseResult.failure(e.getMessage());
        } catch (Exception e) {
            System.err.println("操作异常: " + e.getMessage());
            e.printStackTrace();
            return DatabaseResult.failure("系统异常: " + e.getMessage());
        }
    }

    /**
     * 执行查询并返回结果列表
     */
    protected <T> DatabaseResult<List<T>> executeQuery(String sql,
                                                       Function<ResultSet, T> mapper,
                                                       Object... params) {
        return safeExecute(() -> {
            List<T> results = new ArrayList<>();
            Connection conn = null;
            PreparedStatement pstmt = null;
            ResultSet rs = null;

            try {
                conn = DatabaseConfig.getConnection();
                pstmt = conn.prepareStatement(sql);

                for (int i = 0; i < params.length; i++) {
                    pstmt.setObject(i + 1, params[i]);
                }

                rs = pstmt.executeQuery();
                while (rs.next()) {
                    results.add(mapper.apply(rs));
                }

                return results;
            } finally {
                closeResources(conn, pstmt, rs);
            }
        });
    }

    /**
     * 执行插入操作，返回自增ID
     */
    protected DatabaseResult<Long> executeInsert(String sql, Object... params) {
        return safeExecute(() -> {
            Connection conn = null;
            PreparedStatement pstmt = null;
            ResultSet rs = null;

            try {
                conn = DatabaseConfig.getConnection();
                pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);

                for (int i = 0; i < params.length; i++) {
                    pstmt.setObject(i + 1, params[i]);
                }

                int affectedRows = pstmt.executeUpdate();
                if (affectedRows == 0) {
                    throw new SQLException("插入失败，未影响任何行");
                }

                rs = pstmt.getGeneratedKeys();
                if (rs.next()) {
                    return rs.getLong(1);
                } else {
                    throw new SQLException("插入失败，未获取到自增ID");
                }
            } finally {
                closeResources(conn, pstmt, rs);
            }
        });
    }

    /**
     * 执行更新操作
     */
    public DatabaseResult<Integer> executeUpdate(String sql, Object... params) {
        return safeExecute(() -> {
            Connection conn = null;
            PreparedStatement pstmt = null;

            try {
                conn = DatabaseConfig.getConnection();
                pstmt = conn.prepareStatement(sql);

                for (int i = 0; i < params.length; i++) {
                    pstmt.setObject(i + 1, params[i]);
                }

                return pstmt.executeUpdate();
            } finally {
                closeResources(conn, pstmt, null);
            }
        });
    }

    /**
     * 执行查询返回单个值
     */
    protected <T> DatabaseResult<T> executeQuerySingle(String sql,
                                                       Function<ResultSet, T> mapper,
                                                       Object... params) {
        return safeExecute(() -> {
            Connection conn = null;
            PreparedStatement pstmt = null;
            ResultSet rs = null;

            try {
                conn = DatabaseConfig.getConnection();
                pstmt = conn.prepareStatement(sql);

                for (int i = 0; i < params.length; i++) {
                    pstmt.setObject(i + 1, params[i]);
                }

                rs = pstmt.executeQuery();
                if (rs.next()) {
                    return mapper.apply(rs);
                }
                return null;
            } finally {
                closeResources(conn, pstmt, rs);
            }
        });
    }

    /**
     * 关闭资源
     */
    protected void closeResources(Connection conn, PreparedStatement pstmt, ResultSet rs) {
        if (rs != null) {
            try { rs.close(); } catch (SQLException e) { /* 忽略 */ }
        }
        if (pstmt != null) {
            try { pstmt.close(); } catch (SQLException e) { /* 忽略 */ }
        }
        if (conn != null) {
            try { conn.close(); } catch (SQLException e) { /* 忽略 */ }
        }
    }

    /**
     * 数据库操作接口
     */
    @FunctionalInterface
    protected interface DatabaseOperation<T> {
        T execute() throws SQLException;
    }

    /**
     * 数据库结果包装类
     */
    public static class DatabaseResult<T> {
        private final boolean success;
        private final T data;
        private final String error;

        private DatabaseResult(boolean success, T data, String error) {
            this.success = success;
            this.data = data;
            this.error = error;
        }

        public static <T> DatabaseResult<T> success(T data) {
            return new DatabaseResult<>(true, data, null);
        }

        public static <T> DatabaseResult<T> failure(String error) {
            return new DatabaseResult<>(false, null, error);
        }

        public boolean isSuccess() { return success; }
        public T getData() { return data; }
        public String getError() { return error; }

        public T getDataOrElse(T defaultValue) {
            return success ? data : defaultValue;
        }

        public void ifSuccess(java.util.function.Consumer<T> consumer) {
            if (success && data != null) {
                consumer.accept(data);
            }
        }

        public void ifFailure(java.util.function.Consumer<String> consumer) {
            if (!success) {
                consumer.accept(error);
            }
        }
    }
}