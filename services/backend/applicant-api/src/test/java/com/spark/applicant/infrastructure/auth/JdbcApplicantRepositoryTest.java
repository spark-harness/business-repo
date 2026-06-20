package com.spark.applicant.infrastructure.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.spark.applicant.domain.applicant.Applicant;
import com.spark.applicant.domain.applicant.PhoneNumber;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcApplicantRepositoryTest {
    @Test
    void findOrCreateByPhoneNumber_whenPhoneAlreadyExists_shouldReturnStableApplicant() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:applicant-repo;DB_CLOSE_DELAY=-1");
        dataSource.setDriverClassName("org.h2.Driver");
        JdbcApplicantRepository repository = new JdbcApplicantRepository(new JdbcTemplate(dataSource), true);
        PhoneNumber phoneNumber = new PhoneNumber("+852", "91234567");

        Applicant first = repository.findOrCreateByPhoneNumber(phoneNumber);
        Applicant second = repository.findOrCreateByPhoneNumber(phoneNumber);

        assertThat(second.applicantId()).isEqualTo(first.applicantId());
        assertThat(second.phoneNumber()).isEqualTo(phoneNumber);
    }
}
