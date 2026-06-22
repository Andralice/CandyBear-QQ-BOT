package com.start.repository;

import javax.sql.DataSource;

/**
 * Repository 层统一接口。
 * 所有数据访问类必须实现此接口，确保 DataSource 注入方式一致。
 */
public interface Repository {
    DataSource getDataSource();
}
