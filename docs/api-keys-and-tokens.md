# API keys and access tokens

A program, a script, or another service can call the API as a user, with nobody signed in. It does so with an **API
key**, or with an **access token** exchanged for one. This page shows how, with examples to copy. How keys and tokens
work inside the runtime is in [the design notes](design/api-keys.md).

The examples use `https://app.example.com` for the deployment. Every API path starts with `/kda`.

## Key or token

- **An API key** is a long-lived secret, `kdr_` followed by 56 more characters. Use it for your own scripts,
  where the program that holds it is the only one that sees it.
- **An access token** is a signed credential that lasts a day by default. Get one by sending your key once, then send
  the token instead. Use a token when the credential passes through another service on its way, or when that service
  must check who is calling. Any service can check a token without calling back here, and the long-lived key never
  leaves your program.

**You never have to use a token.** Sending the key itself on every request is fully supported, and for a program that
calls this deployment directly it is the simpler choice and gives up nothing. Revoking a key takes effect on its next
request, and this deployment checks a token's key on every request anyway, so a token is no safer and no faster here.
Reach for one only when another service needs to check the credential, or when the key should not travel with each
request.

Either one acts as the key's user, with that user's permissions as they are now. A **read-only** key, and every token
exchanged for one, can only look at things (GET requests). A full key can do anything its user can, except what needs a
person signed in: managing keys, signing in or out, switching users or clients, or changing a password.

## Get a key

Make one under **API keys** on your profile, or an administrator makes one for you from the Users page. The whole key
is shown once, when it is made. Copy it then and keep it as you would a password: in your secret store or an
environment variable, never in source code or a URL.

## Call with a key

Send the key in one request header, `Authorization`, after the word `Bearer` and a space. For a GET, that header is all
the request needs: no cookie, no other header, nothing in the address. Here is the request as it goes over the wire,
with a made-up key:

```http
GET /kda/auth/self/info HTTP/1.1
Host: app.example.com
Authorization: Bearer kdr_Qx7mP2vLk9Ra_f3Jd8sKq1ZpW6tYh0NcB4rXe7uGm2LaV9iOy5kTn-Hw
```

- **The whole key, as it was shown,** `kdr_` included: 60 characters, with no quotes and no spaces inside it.
- **`Bearer` first.** Without it the key is not read at all (see
  [If the header is not recognized](#if-the-header-is-not-recognized)).
- **In the header, never in the address.** A key in the query string is never read as a credential, and it would be
  written into every log the address passes through.
- **A request with a body** says what the body is: `Content-Type: application/json` for JSON. Without it the body is
  not read.

To try it with curl, from a shell where `KDR_API_KEY` holds the key:

```bash
curl -H "Authorization: Bearer $KDR_API_KEY" https://app.example.com/kda/auth/self/info
```

The call to try first is `/kda/auth/self/info`, which answers with who the request acted as. Its answer, cut down to
what matters here:

```json
{
  "requestUri": "GET:/kda/auth/self/info",
  "results": {
    "client": "acme",
    "publicName": "pat@example.com",
    "userId": 1042,
    "roles": ["user"]
  }
}
```

Check that `userId` is the key's user. A `userId` of `0` means the request acted as nobody: the header was not
recognized, and this endpoint, which is open to everyone, answered anyway. Every other call works the same way, with
the same header.

## Exchange a key for a token

Post the key to `/kda/auth/token`, asking for the `client_credentials` grant. This is the OAuth 2.0 client-credentials
grant, so an OAuth client library can do it for you. On the wire:

```http
POST /kda/auth/token HTTP/1.1
Host: app.example.com
Authorization: Bearer kdr_Qx7mP2vLk9Ra_f3Jd8sKq1ZpW6tYh0NcB4rXe7uGm2LaV9iOy5kTn-Hw
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials
```

A JSON body works too, with its own content type:

```http
POST /kda/auth/token HTTP/1.1
Host: app.example.com
Authorization: Bearer kdr_Qx7mP2vLk9Ra_f3Jd8sKq1ZpW6tYh0NcB4rXe7uGm2LaV9iOy5kTn-Hw
Content-Type: application/json

{"grant_type": "client_credentials"}
```

The same with curl, which sends `-d` as a form-encoded body:

```bash
curl -H "Authorization: Bearer $KDR_API_KEY" -d grant_type=client_credentials \
  https://app.example.com/kda/auth/token
```

The answer is the token and how long it lasts, in seconds, as the whole body:

```http
HTTP/1.1 200 OK
Content-Type: application/json
Cache-Control: no-store
Pragma: no-cache

{
  "access_token": "eyJhbGciOiJFUzI1NiIsInR5cCI6ImF0K2p3dCIs…",
  "token_type": "Bearer",
  "expires_in": 86400,
  "scope": "read"
}
```

- **The token is the value of `access_token`** alone: about 400 characters on one line, starting `eyJ`, with two dots
  in it. Not the whole answer.
- **The scope** is the key's access: `read` or `full`.
- **It never outlasts the key.** A key with two hours left gives a token that lasts two hours.
- **To get another,** ask again with the key. There is no refresh token: the key is what you refresh with. Ask a few
  minutes before `expires_in` runs out, rather than waiting for a refusal.
- **Keep the token in memory.** It is a credential too, if a short-lived one. `Cache-Control: no-store` asks everything
  between here and your program not to keep a copy.

## Call with a token

Send the token exactly where the key went, in the same header, after the same word:

```http
GET /kda/auth/self/info HTTP/1.1
Host: app.example.com
Authorization: Bearer eyJhbGciOiJFUzI1NiIsInR5cCI6ImF0K2p3dCIs…
```

With curl, where `TOKEN` holds the `access_token` value:

```bash
curl -H "Authorization: Bearer $TOKEN" https://app.example.com/kda/auth/self/info
```

A token is held to the same rules as its key, and while it lasts it acts as the key would. It stops working here as
soon as its key is revoked, its key expires, or its user is disabled. If the key is made read-only, its tokens become
read-only too.

## A program that keeps its token fresh

In Python, with nothing but the standard library:

```python
import json, os, time, urllib.parse, urllib.request

BASE = "https://app.example.com/kda"
KEY = os.environ["KDR_API_KEY"]
_token, _expires = None, 0.0

def token():
    """A token good for at least another five minutes, exchanging the key for a new one when needed."""
    global _token, _expires
    if _token is None or time.time() > _expires - 300:
        body = urllib.parse.urlencode({"grant_type": "client_credentials"}).encode()
        request = urllib.request.Request(f"{BASE}/auth/token", data=body,
                                         headers={"Authorization": f"Bearer {KEY}"})
        with urllib.request.urlopen(request) as answer:
            granted = json.load(answer)
        _token, _expires = granted["access_token"], time.time() + granted["expires_in"]
    return _token

def get(path):
    request = urllib.request.Request(f"{BASE}{path}", headers={"Authorization": f"Bearer {token()}"})
    with urllib.request.urlopen(request) as answer:
        return json.load(answer)

print(get("/auth/self/info")["results"])
```

In JavaScript, on Node 18 or later (or any runtime with `fetch`):

```js
const BASE = 'https://app.example.com/kda';
let cached = { token: null, expires: 0 };

async function token() {
  if (!cached.token || Date.now() > cached.expires - 5 * 60 * 1000) {
    const answer = await fetch(`${BASE}/auth/token`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${process.env.KDR_API_KEY}` },
      body: new URLSearchParams({ grant_type: 'client_credentials' }),
    });
    if (!answer.ok) throw new Error(`token exchange: ${answer.status}`);
    const granted = await answer.json();
    cached = { token: granted.access_token, expires: Date.now() + granted.expires_in * 1000 };
  }
  return cached.token;
}

const me = await fetch(`${BASE}/auth/self/info`, { headers: { Authorization: `Bearer ${await token()}` } });
console.log((await me.json()).results);
```

## Check a token in another service

A service that receives a token can check it with the deployment's published keys alone, with no call back here. The
keys are at `/kda/auth/jwks`, a standard JSON Web Key Set, which most JWT libraries read directly. Fetch it once and
keep it, fetching it again when a token names a key (`kid`) you do not have: the deployment adds a new key a few
minutes before it begins to sign with it.

A token is a JWT signed with **ES256**. Check:

- **the signature**, with the key in the set whose `kid` the token's header names. Accept `ES256` and nothing else.
- **the type**: the header's `typ` is `at+jwt`, so no other kind of JWT passes for an access token.
- **the issuer and the audience**: `iss` and `aud` are both the deployment's public URL (for example
  `https://app.example.com`). Ask its operator if you are unsure what it is.
- **the expiry**: `exp` is later than now.

Its other claims say who it acts for: `sub` is the user's id, `client` the client they belong to, `scope` what the
token may do (`read` or `full`), and `client_id` the id of the key it was exchanged for.

In Node, with only its built-in `crypto`:

```js
import { createPublicKey, verify } from 'node:crypto';

const ISSUER = 'https://app.example.com';
const jwks = await (await fetch(`${ISSUER}/kda/auth/jwks`)).json();

function check(token) {
  const [h, p, s] = token.split('.');
  const header = JSON.parse(Buffer.from(h, 'base64url'));
  const claims = JSON.parse(Buffer.from(p, 'base64url'));
  if (header.alg !== 'ES256' || header.typ !== 'at+jwt') throw new Error('not an access token');
  const jwk = jwks.keys.find((k) => k.kid === header.kid);
  if (!jwk) throw new Error('unknown key: fetch the key set again');
  const key = createPublicKey({ key: jwk, format: 'jwk' });
  const signed = verify('sha256', Buffer.from(`${h}.${p}`), { key, dsaEncoding: 'ieee-p1363' }, Buffer.from(s, 'base64url'));
  if (!signed) throw new Error('bad signature');
  if (claims.iss !== ISSUER || claims.aud !== ISSUER) throw new Error('not issued here, or not for us');
  if (claims.exp * 1000 <= Date.now()) throw new Error('expired');
  return claims;
}
```

In Python, PyJWT (installed with its `crypto` extra) does the same:

```python
import jwt

ISSUER = "https://app.example.com"
keys = jwt.PyJWKClient(f"{ISSUER}/kda/auth/jwks")

def check(token):
    if jwt.get_unverified_header(token).get("typ") != "at+jwt":
        raise ValueError("not an access token")
    key = keys.get_signing_key_from_jwt(token)
    return jwt.decode(token, key.key, algorithms=["ES256"], audience=ISSUER, issuer=ISSUER)
```

**A service checking tokens itself honors one until it expires, even after its key is revoked.** Only this deployment
knows a key was revoked. If that matters to you, keep tokens short: the deployment's operator sets their lifetime
(`KDR_ACCESS_TOKEN_HOURS`).

## If the header is not recognized

A bearer that does not begin with `kdr_`, and is not one of this deployment's tokens, is **ignored, not refused**: the
request goes on as if it carried no header at all, acting as nobody. So a mistake of that kind shows up exactly as a
missing header does:

- an endpoint open to everyone answers as usual, as nobody: `/kda/auth/self/info` says `userId` is `0`;
- an endpoint that needs a signed-in user answers 401, naming the role it requires: "Request requires the 'user' role
  and no user is logged in." (The token exchange says instead to send the key.)

A message like that, rather than "The API key is not valid.", says the header was never read as a credential. The
usual causes:

- **`Bearer` is missing**, or something else stands in its place: `Authorization: kdr_…` alone is not read.
- **Something comes before the `kdr_`**: quotes around the key (`Bearer "kdr_…"`), or the `kdr_` dropped.
- **The whole token answer was sent** instead of its `access_token` value.
- **No header reached the deployment.** Some HTTP clients drop `Authorization` when they follow a redirect to another
  host, so send requests to the deployment's own address.

Everything that does begin with `kdr_` is read as a key, and refused with "The API key is not valid." when it is not
one: a key cut short, a character changed, a secret mistyped, or a key revoked or expired. A token is refused the same
way, as "The access token is not valid.", once it is expired or its key is gone. `Bearer` may be written in any case,
and so may the header's name, as HTTP allows.

## When a call is refused

Every refusal is a JSON body whose `errorMessage` says what was wrong.

| Status | What it means |
| --- | --- |
| 400 | The request is malformed. For the exchange, the `grant_type` is not `client_credentials`, or the body was not read because its `Content-Type` was missing. |
| 401 | "The key or token is not valid": mistyped, expired, revoked, or its user is disabled, each answered the same way on purpose. For a token, get a new one with the key; if that fails too, the key itself is the problem. "No user is logged in" means the header was never read: see [If the header is not recognized](#if-the-header-is-not-recognized). |
| 403 | The key or token is valid but may not do this: a read-only one tried to change something, it called something only a person signed in may do, or a token was sent to `/kda/auth/token` (send the key). |
| 429 | Too many invalid keys or tokens from your address. Wait, and fix whatever is sending them. |
