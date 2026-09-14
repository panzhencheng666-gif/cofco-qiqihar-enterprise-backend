UPDATE production.regional_public_source
SET next_refresh_at = CASE
    WHEN (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')::time < TIME '08:30'
      THEN ((CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')::date + TIME '08:30') AT TIME ZONE 'Asia/Shanghai'
    ELSE (((CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')::date + 1) + TIME '08:30') AT TIME ZONE 'Asia/Shanghai'
END
WHERE active;
