package com.cpdts.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class RuntimeDatabaseGuardTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final RuntimeDatabaseGuard guard = new RuntimeDatabaseGuard(jdbc);

    @Test void permitsCompatibleRestrictedRole() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(false, true);
        assertThatCode(() -> guard.run(null)).doesNotThrowAnyException();
    }

    @Test void rejectsOwnerOrSuperuser() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(true);
        assertThatThrownBy(() -> guard.run(null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("Unsafe runtime database role");
    }

    @Test void rejectsIncompatibleSchema() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(false, false);
        assertThatThrownBy(() -> guard.run(null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("versions 1 through 9");
    }

    @Test void failsClosedWhenHistoryCannotBeRead() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(false).thenThrow(new org.springframework.dao.DataRetrievalFailureException("unavailable"));
        assertThatThrownBy(() -> guard.run(null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("Cannot verify runtime role or schema version");
    }
}
