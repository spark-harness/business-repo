create table if not exists loan_applications (
  application_id varchar(80) primary key,
  applicant_id varchar(80) not null,
  product_code varchar(32) not null,
  status varchar(32) not null,
  current_step varchar(64) not null,
  amount numeric(18,2) not null,
  term_months integer not null,
  purpose varchar(80) not null,
  accepted_quote_id varchar(80) not null,
  accepted_quote_snapshot text not null,
  created_at timestamp not null,
  updated_at timestamp not null
);

create index if not exists idx_loan_applications_applicant_updated_at
  on loan_applications (applicant_id, updated_at);

create table if not exists idempotency_records (
  applicant_id varchar(80) not null,
  operation varchar(40) not null,
  idempotency_key varchar(160) not null,
  request_hash varchar(512) not null,
  application_id varchar(80) not null,
  created_at timestamp not null default current_timestamp,
  primary key (applicant_id, operation, idempotency_key)
);
