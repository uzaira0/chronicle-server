WITH objects(kind, identity, acl) AS (
    SELECT 'relation', quote_ident(relname),
           COALESCE(relacl, acldefault(CASE WHEN relkind = 'S' THEN 'S' ELSE 'r' END::"char", relowner))
    FROM pg_class WHERE relnamespace = 'public'::regnamespace
      AND relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
    UNION ALL
    SELECT 'column', format('%I.%I', relname, attname), attacl
    FROM pg_attribute JOIN pg_class ON oid = attrelid
    WHERE relnamespace = 'public'::regnamespace AND attnum > 0 AND NOT attisdropped
    UNION ALL
    SELECT 'function', format('%I(%s)', proname, pg_get_function_identity_arguments(oid)),
           COALESCE(proacl, acldefault('f', proowner))
    FROM pg_proc WHERE pronamespace = 'public'::regnamespace
)
SELECT (kind, identity, permission.grantor, permission.grantee,
        permission.privilege_type, permission.is_grantable)::text
FROM objects CROSS JOIN LATERAL aclexplode(acl) permission ORDER BY 1
