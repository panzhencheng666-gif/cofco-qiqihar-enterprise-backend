-- Preserve the discovery history, but exclude examination and recruitment documents from business evidence.
UPDATE production.regional_public_source
SET active=false
WHERE source_id LIKE 'search-found-%'
  AND source_name ~ '(笔试|模拟试题|题库|招聘|速记|考试答案)';
