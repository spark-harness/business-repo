create table applicants (
    applicant_id varchar(80) primary key,
    country_code varchar(16) not null,
    phone varchar(64) not null,
    phone_key varchar(96) not null unique,
    created_at timestamp not null,
    updated_at timestamp not null
);
