package com.botfunnel.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class AppExceptionTest {

    @Test
    void gone_setsStatus410_andCarriesCode() {
        AppException ex = AppException.gone("export_purged", "msg");

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.GONE);
        assertThat(ex.getCode()).isEqualTo("export_purged");
        assertThat(ex.getMessage()).isEqualTo("msg");
    }
}
