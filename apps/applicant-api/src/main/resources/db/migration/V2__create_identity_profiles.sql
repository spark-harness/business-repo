create table applicant_identity_profiles (
    applicant_id varchar(80) primary key references applicants(applicant_id),
    hkid_body varchar(16) not null,
    hkid_check_digit varchar(2) not null,
    first_name varchar(120) not null,
    last_name varchar(120) not null,
    chinese_name varchar(120) not null,
    nationality varchar(40) not null,
    date_of_birth varchar(10) not null,
    created_at timestamp not null,
    updated_at timestamp not null
);
