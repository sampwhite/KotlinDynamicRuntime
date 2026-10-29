# Deploying to a lightweight node

This guide puts KotlinDynamicRuntime on a **lightweight node**: one small Linux server with no database server
and nothing in front of it but Caddy, which handles HTTPS. The node keeps its data in an H2 file database and runs
the application as a systemd service of its own user. The reference instance at `dynamicruntime.org` runs this way,
with the `reference` deployment flavor.

**The reference instance is part of the public documentation.** The repository's documents link to it, to try the
demo and to read the documents a running server generates, so it is kept reasonably current with `main`: a merged
change that adds a generated document, or changes one a page relies on, is followed by a deploy. Clearing its data
does not touch those documents, since they are made from code.

The guide has two parts. **One-time setup** is done once per node, partly with `sudo`. **Deploying a build** is
done every time, from your own machine, and needs no `sudo`: `kdr-deploy` does it in one command. Looking at a
node, reading its log, and clearing its data are `kdr-node`'s, from the same machine.

The examples use `example.org` for the domain and `kdr` for the user that runs the application. Put in your own.

## What you need

- **A Linux server** reachable over SSH from your machine, with a user who can use `sudo`. This guide was written
  against Ubuntu 26.04 LTS on a node with 1 CPU, about 1 GB of memory, a 512 MB swap partition, and 13 GB of free
  disk. Less memory than that leaves too little room for the application beside Caddy and the system.
- **A domain** whose DNS records for the bare name and for `www` point at the server. Caddy needs them before it can
  get a certificate.
- **A workspace that builds KotlinDynamicRuntime** on your own machine (see "Getting started" in the README).
- **A Mailgun account**, if the public is to create accounts and sign in. Sign-in is by a code mailed to the
  person's address, and KotlinDynamicRuntime sends mail through Mailgun only. Without one the instance still runs,
  but visitors can only read what is public. If you want another mail provider supported, open an issue asking for
  it in the [KotlinDynamicRuntime repository](https://github.com/sampwhite/KotlinDynamicRuntime/issues).

## One-time setup

### 1. Clear out what ran there before

Before the first deploy, stop anything already on the node that holds a port this setup needs (80 and 443 for
Caddy, 7070 for the application) or that would get in the way of one. That might be a previous service, another web
server, or a port-forwarding rule. What that means depends on the node, so check rather than assume:

```bash
sudo ss -ltnp
```

```bash
sudo iptables -t nat -S
```

The first lists what listens on each port. The second lists port-forwarding rules; a `REDIRECT` or `DNAT` rule for
80 or 443 would send traffic past Caddy.

### 2. Install Java and Caddy

Both come from Ubuntu's own repositories, so unattended upgrades keep them patched. The application needs Java 25:

```bash
sudo apt update
sudo apt install openjdk-25-jre-headless caddy
```

Installing Caddy also starts it, with a placeholder site on port 80. Step 4 replaces it.

### 3. Open the firewall

Allow HTTP and HTTPS. Caddy needs port 80 to get its certificate from Let's Encrypt, and redirects every other
request on it to HTTPS. Leave SSH open, and leave the application's own port closed: only Caddy reaches it, from
the node itself.

```bash
sudo ufw allow 80,443/tcp
```

### 4. Configure Caddy

Caddy gets a certificate from **Let's Encrypt** for the names it serves, and renews it before it expires, with
nothing further to do. Replace `/etc/caddy/Caddyfile` with:

```
{
	email you@example.org
}

example.org, www.example.org {
	reverse_proxy 127.0.0.1:7070
}
```

The address in the first block is where Let's Encrypt writes if a certificate cannot be renewed. Then reload
Caddy:

```bash
sudo systemctl reload caddy
```

Until the application is running, the site answers with an error from Caddy. That is expected.

### 5. Keep the logs small

The application writes its log to standard output, and systemd keeps it in the **journal**, as it does Caddy's.
journald rotates and deletes old entries itself, so there are no log files to clean up. Out of the box it keeps up to a
tenth of the disk, which is more than a small node wants. Cap it, by creating
`/etc/systemd/journald.conf.d/size.conf` (and the directory, if it is not there yet):

```
[Journal]
SystemMaxUse=500M
MaxRetentionSec=1month
```

Then restart the journal so it takes effect:

```bash
sudo systemctl restart systemd-journald
```

That keeps a month of history, or 500 MB if that comes first, which is plenty to look back on what happened.

### 6. Let the user run a service of its own

The application runs as a **systemd user service**: a service belonging to the `kdr` user rather than to the
system. Restarting it then needs no `sudo`, which is what lets a deploy run without it. For a user's services to
run when nobody is signed in, systemd has to be told once:

```bash
sudo loginctl enable-linger kdr
```

That is the last step that needs `sudo`. The rest is done as the `kdr` user.

### 7. Make the directories

Everything the application uses lives under one directory, `~/kdr`, which is the application's **workspace
directory**:

| Path | What it holds |
| --- | --- |
| `releases/` | One directory per deployed build, named for when it was built and from which commit |
| `current` | A link to the release that runs |
| `kdr.env` | The node's settings (step 8) |
| `h2Database/` | The database file, created on the first start. A deploy never touches it. |
| `fileStore/` | Files the application stores, created on the first start; none when they are kept in object storage (see "Keeping files in Linode Object Storage" below) |
| `flavor-overlays/` | The deployment's own wording for its deployment flavors, if any |
| `private/` | Secrets: the mail provider's key (see "Mail and sign-in" below) |

Create the ones that are not created for you:

```bash
mkdir -p ~/kdr/releases ~/kdr/flavor-overlays ~/kdr/private ~/.config/systemd/user
```

### 8. Write the settings

The application reads its settings from environment variables, and the service sets them from `~/kdr/kdr.env`. The
settings for a reference deployment, a public instance for people to look around:

```
JAVA_HOME=/usr/lib/jvm/java-1.25.0-openjdk-amd64
KDR_DEFAULTS_FILE=none
KDR_WORKSPACE_DIR=/home/kdr/kdr
KDR_ENV=dev
KDR_PORT=7070
KDR_BIND_ADDRESS=127.0.0.1
KDR_IN_MEMORY_ONLY=false
KDR_DB_TYPE=h2File
KDR_TEST_INSTANCE=true
KDR_PUBLIC_TEST_DEPLOYMENT=true
KDR_TRUST_ENV_AUTH_HEADER=false
KDR_ASSUME_ENV_AUTH=true
KDR_ENV_AUTH_FROM_ADMIN_LOGIN=true
KDR_ADMIN_EMAIL_DOMAIN=example.org
KDR_PUBLIC_SIGN_IN=realMail
KDR_MAIL_DOMAIN=mg.example.org
KDR_PUBLIC_URL=https://example.org
KDR_LOG_LEVEL=info
KDR_DEPLOY_FLAVORS=reference
```

What each one does:

| Setting | Why |
| --- | --- |
| `JAVA_HOME` | Which Java to run. Ubuntu may keep an older Java as its default; this names Java 25. The directory's name varies with the package, so after installing Java (step 2), list `/usr/lib/jvm` with `ls /usr/lib/jvm` and use the Java 25 directory you find there. |
| `KDR_DEFAULTS_FILE` | `none`: there is no defaults file on a node, and nothing should be read from one. |
| `KDR_WORKSPACE_DIR` | The workspace directory, `~/kdr`, as an absolute path. The database, the file store, the flavor directory and the secrets are found under it. |
| `KDR_ENV` | `dev`, which a public test deployment runs in. |
| `KDR_PORT` | The port the application listens on. Caddy forwards to it. |
| `KDR_BIND_ADDRESS` | `127.0.0.1`: the application listens on the node's own loopback address alone, so nothing outside the node can reach it directly, whatever the firewall says. Only Caddy, on the same node, can. |
| `KDR_IN_MEMORY_ONLY` | `false`, so the data is kept in a database file and survives a restart or a deploy. |
| `KDR_DB_TYPE` | `h2File`, the H2 file database. No database server is needed. |
| `KDR_TEST_INSTANCE` | `true`: a reference deployment is a test instance, so the demonstrations it offers are available. |
| `KDR_PUBLIC_TEST_DEPLOYMENT` | `true`: a test instance the public can reach. It marks every page and mail as being for testing, and hardens what a test instance allows. See "Mail and sign-in" below. |
| `KDR_TRUST_ENV_AUTH_HEADER` | `false`. A public test deployment has to say this explicitly. It is `true` only behind an edge server that strips the header from outside traffic, and this node has none. |
| `KDR_ASSUME_ENV_AUTH` | `true`: a request that arrives with no `X-Forwarded-For` header carries env auth. Caddy adds that header to every request it forwards, so such a request can only have come from the node itself -- you, over SSH. See "Env auth on the node itself" below. |
| `KDR_ENV_AUTH_FROM_ADMIN_LOGIN` | `true`: an administrator on your own mail domain carries env auth when signed in. See "Mail and sign-in" below. |
| `KDR_ADMIN_EMAIL_DOMAIN` | Your own mail domain, whose addresses are made administrators. Never a public mail domain such as `gmail.com`; the node refuses to start with one. |
| `KDR_PUBLIC_SIGN_IN` | `realMail`: visitors sign in and register as on a real deployment, with their codes mailed for real. This needs the mail provider's key. See "Mail and sign-in" below. |
| `KDR_MAIL_DOMAIN` | The domain your mail is sent from: the sending domain set up in your Mailgun account, which its key is for. Mail goes out as `kdr-automail@` that domain. Required whenever the node can send mail, which with the key in place it can; without it the node refuses to start, saying so. |
| `KDR_PUBLIC_URL` | The address people use, for links the application writes into mail. |
| `KDR_LOG_LEVEL` | `info`. Outside the unit tests the application logs at `debug` by default, which helps on a developer's machine and is mostly noise on a public node. It is what decides how fast the log grows. |
| `KDR_DEPLOY_FLAVORS` | `reference`, the deployment flavor that makes the instance say what it is, load the sample clients, and set up the conference demo as it starts. Leave it out for a node that is not a reference deployment. |

**Optional: Google sign-in.** Set `KDR_GOOGLE_CLIENT_ID` to your Google OAuth client id to offer signing in with
Google beside the emailed code. The id is public by design, not a secret. In Google Cloud Console, register your site
(`https://example.org`) as an **Authorized JavaScript origin** for that client id. Without it, the button can appear
and then never finish, and the application cannot tell why.

On a Google account's first sign-in, if Google has verified its address, the sign-in uses the existing account at
that address, or makes one, and links the Google account to it. After that it is the link that counts, so the person
reaches the same account even if their Google address later changes. An address Google has not verified is refused.
Google sign-in follows `KDR_PUBLIC_SIGN_IN` as the emailed code does. It does not replace the mail provider: the
sign-in page still offers the emailed code.

A test instance refuses to start in `dev` unless it is a public test deployment. A public test deployment refuses to
start unless `KDR_TRUST_ENV_AUTH_HEADER` is set, and refuses `KDR_ASSUME_ENV_AUTH` unless `KDR_BIND_ADDRESS` is a
loopback address. So a mistake in these settings stops the start, with a log line saying why, rather than leaving a
test instance open to the public.

Every setting is described by the running node at `/operator/env/reference`, with the value it took.

### 9. Install the service

Write `~/.config/systemd/user/kdr.service`:

```
[Unit]
Description=KotlinDynamicRuntime

[Service]
WorkingDirectory=%h/kdr
EnvironmentFile=%h/kdr/kdr.env
Environment="JAVA_OPTS=-Xmx384m -XX:MaxMetaspaceSize=160m"
ExecStart=%h/kdr/current/bin/launch
Restart=on-failure
RestartSec=5
SuccessExitStatus=143

[Install]
WantedBy=default.target
```

`%h` is the user's home directory. The memory limits leave room on a node of about 1 GB: the heap is capped at
384 MB, and Java's other memory (its classes, its threads) comes on top of that, beside Caddy and the system. A
freshly started reference deployment uses about 300 MB in all. `SuccessExitStatus=143` is how Java exits when it is
stopped (128 plus SIGTERM's 15), so a restart is not logged as a failure. Then enable it, so it starts with the node:

```bash
systemctl --user daemon-reload
```

```bash
systemctl --user enable kdr
```

It has nothing to run yet. The first deploy starts it.

## Deploying a build

**Describe the node once, in a target file.** `kdr-deploy` reads where the node is and the settings it runs with from
`deploy/targets/<name>.properties` in the repository: `host`, `user`, `baseDir` (the node's `~/kdr`, as an absolute
path), `service`, `publicUrl`, and how many releases to keep, then each setting of step 8 as `env.<VARIABLE>=<value>`.
`deploy/targets/dynamicruntime.org.properties` is a complete example. A target file holds no secrets: the node keeps
those in `private/`, which the command never touches.

**Build only what the node runs.** The build happens in your workspace, so it would otherwise include whatever your
workspace's `settings.gradle.kts` includes: your own configuration code (`customConfig`), which the application
would apply on the node as it starts, and any scratch project. So a target names its build with `buildProfile`
(`reference` for dynamicruntime.org), and `kdr-deploy` passes it to Gradle as `-PkdrBuild=reference`. Your settings
file reads that property and leaves your own projects out when it is set. The block to copy is at the end of
`examples/settings.gradle.kts.example`:

```kotlin
val kdrBuild: String? = providers.gradleProperty("kdrBuild").orNull
if (kdrBuild == null) {
    injectComponent(":customConfig", "customConfig")
}
```

A developer build, IntelliJ's included, passes no property, so it still sees every project. Then the node's settings
come only from its `kdr.env` and its flavors.

A node of your own does not need to be in the repository. Keep its target file anywhere, for example
`~/nodes/mynode.properties`, and pass its path instead of a name: `kdr-deploy ~/nodes/mynode.properties`. A relative
path is read from the directory you run the command in. Keep that file outside the checkout rather than uncommitted in
`deploy/targets/`. An untracked file there counts as a change, so every release would be named `-dirty`.

**Then deploy with one command,** from your machine:

```bash
kdr-deploy dynamicruntime.org --dry-run
```

```bash
kdr-deploy dynamicruntime.org
```

The dry run shows what a deploy would do and changes nothing: the release it would make, which release runs now, how
`kdr.env` would change, and which old releases it would remove. A deploy then, after asking:

1. builds the distribution (`./gradlew -PkdrBuild=<buildProfile> :launch:installDist`), which is the release;
2. copies it to a new `releases/<date>-<time>-<commit>/`, with `-dirty` on the end when your checkout had uncommitted
   changes;
3. writes `kdr.env` from the target when it differs, keeping the old one as `kdr.env.prev`;
4. points `current` at the new release and restarts the service;
5. waits for the application to answer on the node, then through the public address;
6. removes releases beyond the target's `keepReleases`, never the running one or the one before it.

It never touches the database (`h2Database/`), the stored files (`fileStore/`), or the secrets (`private/`). It
reaches the node with your own `ssh` and `rsync`, and needs no `sudo` there. If the new release does not answer as
healthy, it is left running and the command says how to go back, so you can look at why first.

**To go back** to the release before the running one, and restart:

```bash
kdr-deploy dynamicruntime.org --rollback
```

The database is not in a release, so going back does not lose data.

**What it does, by hand.** The distribution is `KotlinDynamicRuntime/launch/build/install/launch/`: a `bin/launch`
script and a `lib/` of every jar the application needs, the web application among them. It is self-contained, unlike
the jar `kdr-run` uses, which names jars by where they sit on your machine. Without the command:

```bash
./gradlew -PkdrBuild=reference :launch:installDist
release=$(date +%Y%m%d-%H%M)-$(git -C KotlinDynamicRuntime rev-parse --short HEAD)
rsync -a KotlinDynamicRuntime/launch/build/install/launch/ kdr@example.org:kdr/releases/$release/
ssh kdr@example.org "ln -sfn ~/kdr/releases/$release ~/kdr/current && systemctl --user restart kdr"
ssh kdr@example.org "curl -s http://127.0.0.1:7070/kda/health"
```

## Looking at a node

**`kdr-node` gathers it in one command** (issue #1244), from your machine, with the same target as `kdr-deploy`:

```bash
kdr-node dynamicruntime.org status
```

It reports the service (running or not, since when, its restarts), the release `current` points at and the
releases kept, health, memory and disk, and the node's own view: the JVM's memory and uptime, the active deployment
flavors, and what the boot checks found. It reads that view from the operator pages over SSH, as below, so nobody
signs in. For the log:

```bash
kdr-node dynamicruntime.org logs --lines 200
```

Add `--follow` to keep watching it. `kdr-node dynamicruntime.org restart` restarts the service and waits until it
answers. By hand, over SSH:

| To see | Run on the node |
| --- | --- |
| Whether it is running | `systemctl --user status kdr` |
| What it has logged | `journalctl --user -u kdr -n 100` (add `-f` to follow it) |
| Which build runs | `readlink ~/kdr/current` |
| Memory and disk | `free -m` and `df -h ~` |

The log's first lines after a start name the deployment flavors and, for a reference deployment, the conference
demo being set up.

**What the application reports about itself.** A request made on the node itself can read the operator pages without
signing in (issue #1255). It counts as a direct request: it carries no `X-Forwarded-For` or `Forwarded` header, and
it comes from the node's own address. For example, the node's identity, uptime and memory:

```bash
ssh kdr@example.org "curl -s http://127.0.0.1:7070/kda/operator/system/info"
```

| To see | Path |
| --- | --- |
| The node, its uptime, and the JVM's memory | `/kda/operator/system/info` |
| Every setting, with the value it took | `/kda/operator/env/reference` |
| What the checks found as it started | `/kda/operator/boot/checks` |
| The database's tables | `/kda/operator/db/tables` |

Only reads: what the operator pages change, such as reloading a cache, still takes an operator who has signed in.
`KDR_DIRECT_ACCESS` decides where a direct request may come from: `loopback` (the default) for the node itself,
`network` for any address, on a node that only its own private network can reach, or `off`. Use `off` on a machine
shared with other users or services.

**Logs.** The log is the service's entries in the journal, kept as step 5 set: a month, or 500 MB. To see how much
the journal holds, or to look further back:

| To see | Run on the node |
| --- | --- |
| How much the journal holds | `journalctl --disk-usage` |
| Everything since a time | `journalctl --user -u kdr --since "2 hours ago"` |
| Caddy's log | `journalctl -u caddy -n 100` (with `sudo`, or as a member of the `adm` group) |

## Clearing the data

A reference deployment's data is temporary, and is cleared from time to time. One command does it (issue #1244):

```bash
kdr-node dynamicruntime.org reset-data --dry-run
```

```bash
kdr-node dynamicruntime.org reset-data
```

The dry run shows what would go and changes nothing: how many files each bucket would lose and keep, and the size of
the database. The reset then, after asking:

1. stops the service;
2. empties the file store: every declared bucket, whether in `fileStore/` or in object storage (below);
3. removes the database, `h2Database/`;
4. starts the service, and waits until it answers on the node and through the public address.

**The buckets themselves stay.** The application never creates one, and it refuses to start without them.

**The web assets of clients defined in source stay too.** The sample's Acme is one. A client's favicon and brand mark
live only in the file store, and a reset brings such a client back with the same id, so its images come back with it.
A client created on the site is not brought back, so its images go with everything else.

The emptying is done by a small command in the deployed release (`ClearFileStore`), run on the node under its own
settings, so nothing is installed for it. The application creates an empty database as it starts, and the
`reference` flavor sets up the conference demo again. Removing `h2Database/` also removes H2's trace file
(`kdr.dat.trace.db`), which H2 writes beside the database when it runs into errors.

By hand, with the files in `fileStore/`:

```bash
systemctl --user stop kdr
rm -rf ~/kdr/h2Database ~/kdr/fileStore
systemctl --user start kdr
```

That removes the web assets too. A node that keeps its files in object storage has no `fileStore/`, so its buckets
are emptied with the provider's console or an S3 tool, while the service is stopped.

## Env auth on the node itself

With `KDR_ASSUME_ENV_AUTH`, a request with no `X-Forwarded-For` header carries **env auth**: the test surface, such
as becoming any user, and what an operator can look at. That is safe here for two reasons, and both must hold:

- **The application listens on loopback alone** (`KDR_BIND_ADDRESS=127.0.0.1`), so the only way to reach it from
  outside is through Caddy. The node will not start with the one setting and not the other.
- **Caddy always adds `X-Forwarded-For`** to a request it forwards, naming the visitor, and a visitor cannot remove
  it. A visitor who sends one of their own only makes it present.

If you put something else in front of the application, it too must always set `X-Forwarded-For`, or every visitor
would carry env auth and could read the operator pages. And anything that runs on the node itself can reach the
application, and carries env auth too, which is fine on a node that does nothing else.

## Mail and sign-in

A **public test deployment** treats people in two ways.

- **Someone with env auth** (a developer or operator trusted by an edge, or by an administrator's login, below)
  gets the test surface: becoming any user, the simulations, the debug pages.
- **Everyone else** uses the application as on a real deployment, or is refused every sign-in and registration,
  which is the default.

What a caller without env auth may do is `KDR_PUBLIC_SIGN_IN`:

| Value | What it allows |
| --- | --- |
| `blocked` (the default) | Nobody without env auth signs in or registers. Visitors read what is public: the home page and the documents. |
| `realMail` | People sign in and register as on a real deployment, with their codes mailed for real. A visitor who registers is a user of the `public` client, and can make a client of their own there. |

A reference deployment uses `realMail`, so that visitors can try it, not just read about it. Two things come with
that:

- **It needs the mail provider's key** (below). Sign-in is by a code mailed to the address, so without the key a
  visitor is told a code is on its way and none arrives.
- **Anyone can make the node send mail**, to any address typed into the sign-in form. The application limits it:
  20 codes per visitor's network address in 15 minutes, and 5 per mail address in an hour. A visitor's address is
  the one Caddy names in `X-Forwarded-For`, which a visitor cannot set. There is no limit across all visitors
  together, so watch the provider's dashboard for unusual volume.

Accounts, like everything else, go when the data is cleared. A reference deployment says so on its sign-in page.

**An administrator carries env auth by signing in**, with `KDR_ENV_AUTH_FROM_ADMIN_LOGIN` and
`KDR_ADMIN_EMAIL_DOMAIN` set, as the reference settings above do. An address on that domain may sign in whatever
`KDR_PUBLIC_SIGN_IN` says, and is mailed its code for real. Once signed in it carries env auth if its user holds the
`allClients` capability (an administrator of every client). To give your own address that capability, before you
first sign in, ask the node itself, which carries env auth for you:

```bash
ssh kdr@example.org "curl -s -X POST -H 'Content-Type: application/json' -d '{\"email\":\"you@example.org\",\"level\":\"admin\",\"capabilities\":[\"allClients\"]}' http://127.0.0.1:7070/kda/fixture/becomeUser"
```

This creates the user, or finds it. Then sign in at `https://example.org` with that address. Your sign-in code is
mailed to you.

**Mail needs the provider's key.** It goes in `~/kdr/private/secrets.properties`, which only the `kdr` user should
read:

```
mailgunApiKey=<your Mailgun API key>
```

```bash
chmod 600 ~/kdr/private/secrets.properties
```

Without the key nothing is sent, so nobody can be mailed a code. With it, the node also needs `KDR_MAIL_DOMAIN`
(above), the domain the mail is sent from. For mail to reach inboxes rather than spam folders, that domain's DNS needs
the records Mailgun asks for (SPF and DKIM); Mailgun's dashboard shows which are missing. To send from a different
address, or through Mailgun's EU endpoint, configuration code built for that deployment sets `appFromAddress` or
`mailgunUri`, which take the place of what the domain gives. Such code is a project your settings file injects under
the deployment's build profile (`if (kdrBuild == "reference") ...`), not your workspace's own `customConfig`, which a
deploy build leaves out.

## Keeping files in Linode Object Storage

By default the node keeps the files the application stores -- a client's tab icon and brand mark, its file refs, the
sample's uploads -- in `~/kdr/fileStore`, on its own disk. They can be kept in **Linode (Akamai) Object Storage**
instead, which is S3-compatible: the application's `s3` file store, pointed at Linode. That keeps them off the node's
disk and lets them outlive the node. The database stays on the node either way. This is optional, and nothing below
is needed to run without it.

**1. Make the buckets**, in Linode's Cloud Manager, under Object Storage, in the region nearest the node. The
application creates none, and refuses to start while one it uses is missing. It uses one bucket per kind of file,
each named with a prefix of your own -- bucket names are shared with Linode's other customers, so pick one nobody
else will have, such as `examplesite-`:

| Bucket | Holds |
| --- | --- |
| `<prefix>web-assets` | Each client's tab icon and brand mark, and its file refs |
| `<prefix>sample-files` | Uploads through the sample's demo file endpoints, which a `dev` node such as this one loads |

**2. Make an access key** under Object Storage, Access Keys. Choose **limited access** and give it read/write on
those buckets only. Linode shows the secret once, and beside it the key's **S3 endpoint** for the region, such as
`us-ord-10.linodeobjects.com` for Chicago. Use that hostname below, exactly: Linode's newer endpoints carry a digit
the region's own name does not (`us-ord-10`, where the region is `us-ord`), and a key is known only at its own
endpoint, so the right key at a neighbouring hostname is refused as one that "does not exist in our records".

**3. Put the key on the node**, as the AWS SDK's credentials file of the `kdr` user. Credentials never go in
`kdr.env` or the target file, which is in the repository:

```bash
mkdir -p ~/.aws && chmod 700 ~/.aws
nano ~/.aws/credentials
```

```
[default]
aws_access_key_id = <the access key>
aws_secret_access_key = <the secret key>
```

```bash
chmod 600 ~/.aws/credentials
```

**4. Name the store in the target file**, beside the other settings, with the key's endpoint and your prefix. The
region is the endpoint hostname's first part:

```
env.KDR_FILE_STORE_TYPE=s3
env.KDR_S3_ENDPOINT=https://us-ord-10.linodeobjects.com
env.KDR_S3_REGION=us-ord-10
env.KDR_S3_BUCKET_PREFIX=examplesite-
```

Then deploy (`kdr-deploy <target>`), which writes them into `kdr.env` and restarts the service.

**5. Check it.** The startup line in the log names the store (`File store: s3 (S3 at
https://us-ord-10.linodeobjects.com (us-ord-10), buckets named 'examplesite-...'), 2 bucket(s) declared.`), and a
bucket that is missing, or that the key cannot use, is named in the startup failure instead -- a `403 Forbidden` there
is most often the key at the wrong endpoint (step 2). Then upload a client's favicon on the site and see it in the
tab. Once the node runs this way, `~/kdr/fileStore` holds nothing the application reads, and can be removed.
