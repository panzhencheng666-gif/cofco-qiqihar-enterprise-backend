# Private evidence photos are a shared bounded context

Production, market, and logistics records each retain their own business aggregates, but
field-photo metadata, integrity, access control, and watermarked representation are owned
by one private Evidence context. We chose a shared context rather than duplicating three
attachment implementations so that a mandatory evidence rule has identical security and
audit semantics across every submission domain.
