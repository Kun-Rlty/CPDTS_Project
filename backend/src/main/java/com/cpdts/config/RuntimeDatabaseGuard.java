package com.cpdts.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Read-only startup checks; migrations and detailed grant review remain operator tasks. */
@Component
public final class RuntimeDatabaseGuard implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    public RuntimeDatabaseGuard(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Boolean unsafe = jdbc.queryForObject("""
                SELECT r.rolsuper OR EXISTS (
                  SELECT 1 FROM (
                    SELECT n.nspowner AS owner FROM pg_namespace n WHERE n.nspname = 'public'
                    UNION SELECT d.datdba FROM pg_database d WHERE d.datname = current_database()
                    UNION SELECT c.relowner FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public'
                    UNION SELECT p.proowner FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public'
                    UNION SELECT t.typowner FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace WHERE n.nspname='public'
                  ) owners WHERE pg_has_role(current_user, owners.owner, 'MEMBER')
                ) FROM pg_roles r WHERE r.rolname = current_user
                """, Boolean.class);
            if (!Boolean.FALSE.equals(unsafe)) {
                throw new IllegalStateException("Unsafe runtime database role: superuser, owner, or member of an owning role");
            }
            Boolean compatible = jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE success AND version IN ('1','2','3','4','5','6','7','8','9')) = 9
                   AND count(*) FILTER (WHERE NOT success) = 0
                   AND count(*) FILTER (WHERE version IS NOT NULL AND version NOT IN ('1','2','3','4','5','6','7','8','9')) = 0
                FROM public.flyway_schema_history
                """, Boolean.class);
            if (!Boolean.TRUE.equals(compatible)) {
                throw new IllegalStateException("Incompatible database schema: successful Flyway versions 1 through 9 are required");
            }
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Cannot verify runtime role or schema version; check database access and Flyway history", e);
        }
    }
}
