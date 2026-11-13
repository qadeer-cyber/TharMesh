-- Optional: real ECDSA verify (requires plpython3u + cryptography). Not for PGlite.
create or replace function tharmesh_private.verify_ecdsa_p256_spki(
    spki_b64 text,
    message bytea,
    der_sig_b64 text
)
returns boolean
language plpython3u
immutable
strict
as $$
import base64
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.backends import default_backend

def b64url_decode(s):
    s = s.replace('-', '+').replace('_', '/')
    pad = (4 - len(s) % 4) % 4
    s += '=' * pad
    return base64.b64decode(s)

try:
    pub = serialization.load_der_public_key(b64url_decode(spki_b64), backend=default_backend())
    der_sig = b64url_decode(der_sig_b64)
    pub.verify(der_sig, message, ec.ECDSA(hashes.SHA256()))
    return True
except Exception:
    return False
$$;

revoke all on function tharmesh_private.verify_ecdsa_p256_spki(text, bytea, text) from public, anon, authenticated;
