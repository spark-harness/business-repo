package com.spark.applicant.application.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spark.applicant.domain.profile.Nationality;
import com.spark.applicant.infrastructure.profile.InMemoryIdentityProfileRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IdentityProfileUseCaseTest {
    private UpsertIdentityProfileUseCase upsertUseCase;
    private GetIdentityProfileUseCase getUseCase;

    @BeforeEach
    void setUp() {
        InMemoryIdentityProfileRepository repository = new InMemoryIdentityProfileRepository();
        Clock clock = Clock.fixed(Instant.parse("2026-06-28T04:00:00Z"), ZoneId.of("Asia/Hong_Kong"));
        upsertUseCase = new UpsertIdentityProfileUseCase(repository, clock);
        getUseCase = new GetIdentityProfileUseCase(repository);
    }

    @Test
    void upsert_whenIdentityProfileIsValid_shouldSaveAndReturnProfile() {
        IdentityProfileResult result = upsertUseCase.upsert(validCommand());

        assertThat(result.profile().applicantId()).isEqualTo("applicant_001");
        assertThat(result.profile().hkidBody()).isEqualTo("A123456");
        assertThat(result.profile().hkidCheckDigit()).isEqualTo("3");
        assertThat(result.profile().nationality()).isEqualTo(Nationality.HONG_KONG);
        assertThat(getUseCase.get(new GetIdentityProfileCommand("applicant_001")).empty()).isFalse();
    }

    @Test
    void get_whenProfileDoesNotExist_shouldReturnEmptyResult() {
        GetIdentityProfileResult result = getUseCase.get(new GetIdentityProfileCommand("applicant_001"));

        assertThat(result.empty()).isTrue();
        assertThat(result.profile()).isNull();
    }

    @Test
    void upsert_whenHkidCheckDigitIsInvalid_shouldRejectWithoutSaving() {
        UpsertIdentityProfileCommand command = validCommand().withHkidCheckDigit("4");

        assertThatThrownBy(() -> upsertUseCase.upsert(command))
                .isInstanceOf(IdentityProfileException.class)
                .hasMessage("hkid_invalid");
        assertThat(getUseCase.get(new GetIdentityProfileCommand("applicant_001")).empty()).isTrue();
    }

    @Test
    void upsert_whenNameContainsNonEnglishLetters_shouldReject() {
        UpsertIdentityProfileCommand command = validCommand().withFirstName("Ada1");

        assertThatThrownBy(() -> upsertUseCase.upsert(command))
                .isInstanceOf(IdentityProfileException.class)
                .hasMessage("validation_error");
    }

    @Test
    void upsert_whenDateOfBirthIsYoungerThan18InHongKongDate_shouldReject() {
        UpsertIdentityProfileCommand command = validCommand().withDateOfBirth("2008-06-29");

        assertThatThrownBy(() -> upsertUseCase.upsert(command))
                .isInstanceOf(IdentityProfileException.class)
                .hasMessage("age_out_of_range");
    }

    @Test
    void upsert_whenDateOfBirthIsOlderThan60InHongKongDate_shouldReject() {
        UpsertIdentityProfileCommand command = validCommand().withDateOfBirth("1965-06-28");

        assertThatThrownBy(() -> upsertUseCase.upsert(command))
                .isInstanceOf(IdentityProfileException.class)
                .hasMessage("age_out_of_range");
    }

    @Test
    void upsert_whenCalledAgainForSameApplicant_shouldOverwriteCurrentProfile() {
        upsertUseCase.upsert(validCommand());

        IdentityProfileResult result = upsertUseCase.upsert(validCommand().withFirstName("Grace"));

        assertThat(result.profile().firstName()).isEqualTo("Grace");
        assertThat(getUseCase.get(new GetIdentityProfileCommand("applicant_001")).profile().firstName())
                .isEqualTo("Grace");
    }

    private UpsertIdentityProfileCommand validCommand() {
        return new UpsertIdentityProfileCommand(
                "applicant_001",
                "A123456",
                "3",
                "Ada",
                "Lovelace",
                "陳小明",
                Nationality.HONG_KONG,
                "1990-01-15");
    }
}
