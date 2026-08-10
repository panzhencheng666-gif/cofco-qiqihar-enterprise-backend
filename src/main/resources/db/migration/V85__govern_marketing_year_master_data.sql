CREATE TABLE platform.marketing_year (
    code varchar(20) PRIMARY KEY,
    name varchar(100) NOT NULL UNIQUE,
    starts_on date NOT NULL,
    ends_on date NOT NULL,
    sort_order integer NOT NULL UNIQUE,
    CHECK (ends_on >= starts_on)
);

INSERT INTO platform.marketing_year(code, name, starts_on, ends_on, sort_order)
VALUES ('2026/27', '2026/27营销年度', DATE '2026-07-01', DATE '2027-06-30', 202627);

ALTER TABLE platform.business_period
    ADD COLUMN marketing_year_code varchar(20)
        REFERENCES platform.marketing_year(code);

UPDATE platform.business_period
SET marketing_year_code = '2026/27'
WHERE starts_on >= DATE '2026-07-01'
  AND ends_on <= DATE '2027-06-30';

ALTER TABLE platform.business_period
    ALTER COLUMN marketing_year_code SET NOT NULL;
