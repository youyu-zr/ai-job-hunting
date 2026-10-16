package com.maple.ai.job.hunting.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Date;

@Getter
@Configuration
public class BetaAccessConfig {

    private final Date autoGrantUntil;

    public BetaAccessConfig(@Value("${beta.auto-grant.until:}") String autoGrantUntilText) {
        this.autoGrantUntil = parseAutoGrantUntil(autoGrantUntilText);
    }

    public boolean isAutoGrantActive(Date now) {
        return autoGrantUntil != null && now != null && autoGrantUntil.after(now);
    }

    public Date resolveExpiry(Date activatedAt) {
        if (activatedAt == null) {
            throw new IllegalArgumentException("内测开始时间不能为空");
        }
        if (!isAutoGrantActive(activatedAt)) {
            throw new IllegalArgumentException("内测开放期限已结束");
        }
        return new Date(autoGrantUntil.getTime());
    }

    private static Date parseAutoGrantUntil(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Date.from(OffsetDateTime.parse(value.trim()).toInstant());
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                    "beta.auto-grant.until 必须是带时区的 ISO-8601 时间，例如 2026-12-30T23:59:59.999+08:00",
                    exception);
        }
    }
}
