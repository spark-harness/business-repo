create table if not exists quotes (
  quote_id varchar(80) primary key,
  applicant_id varchar(80) not null,
  product_code varchar(32) not null,
  amount numeric(18,2) not null,
  term_months integer not null,
  purpose varchar(80) not null,
  monthly numeric(18,2) not null,
  apr numeric(8,4) not null,
  total_interest numeric(18,2) not null,
  total_payable numeric(18,2) not null,
  valid_until timestamp not null,
  trace_id varchar(128),
  created_at timestamp not null
);

create index if not exists idx_quotes_applicant_id_created_at
  on quotes (applicant_id, created_at);
