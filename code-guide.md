## Overview

This file specifies how general approach of how we implement code. Some choices are not consistent with
standard practice and deliberately violate those standards as an experiment.

### Meta-Rules

These are general rules and not specific guidance.

* Kotlin is used as much as possible. For example, that means configuration files will not be YAML but instead
be Kotlin files using an appropriate config DSL. And in many cases, what would normally be a resources
file in other coding projects will instead be written as a Kotlin class. 
Non-disposable scripts will be written as Kotlin source code. We will try to avoid python and shell script tooling.

* We will minimize the use of reflection. That means that for serialization to/from realized
Kotlin objects, we will use explicit extraction and renderings from/into Map constructs. We will discuss
this in more detail later on. Reflection can be used to discover whether a class exists, such as
an override configuration class, but such classes should always implement an expected interface whose
methods do not require reflection to call.

* We will minimize the use of `internal` or `private`. In the cases where we want to signal that a
declaration should be thought of as 'internal' or 'private' even though it is left with open visibility,
we annotate it with `@KdrInternal` or `@KdrPrivate` (defined in the `common` project). These are
source-level markers only and do not change actual visibility. They should be used sparingly. We will
still use the real `private` or `internal` keyword in the occasional case where enforcement genuinely
matters, such as a cache that is mutated without first synchronizing.

* This code base will use environment variables more than is typical to create variations in configuration on startup.
The full set of variables in use is documented in [`environment-variables.md`](environment-variables.md).

* Generally, we will choose to write our own code instead of using a library to implement a feature. We will still
use core libraries such as Jetty and libraries to interact with third parties such as AWS. If we do use libraries,
we will tend to use commonly used libraries that have been stable for years.

* We are not going to use Kotlin's async approach that requires yielding the current execution stack to others.
Instead, we will use traditional synchronous call patterns and use Java's virtual threads if we need to support
high-volume requests.

* Recursive functions that walk externally supplied or map/list-shaped data must carry a `depth` parameter and
fail fast once it exceeds a sane maximum, rather than risk a `StackOverflowError`. This guards against both bad
data (a self-referential `Map`/`List`, or pathologically deep input) and bad code (an accidental cycle in our
own logic) — we have found the check valuable on both counts. Pass the depth explicitly, defaulting the
top-level entry to 0, increment it at each recursive step, and throw a `KdrException` when it passes the limit.
Pick a limit well above any legitimate nesting (20–50 is typical). See `SchParser.parseNode` and `JsonUtil`
(its formatting/parsing nesting guards) for prior art.

* Deployments will be done by a pull from a git repository, and a Gradle build will be done on the source code
as part of the launch of an application. There will be no CI process to generate deployable "jar" files. This
means a deployment agent can add Kotlin code specific to the deployment and have it picked up by the build. References
to this additional code can be loaded by adusting the `settings.gradle.kts` file. That file is always defined
in a directory that is a parent to the source code directory and is not version-controlled. Examples of the
settings file will be provided in the source code. This is how a deployment can inject configuration into
the deployed code, though most of the common variations should be done by examining environment variables.

* We will support dynamically discovering parts of our code base, using reflection, and use plugin architectures 
to allow "compoents" to extend behavior. At the time this document was written, this part of the code base is 
yet to be written.

* Many different applications will be built from the same source code. Configuration and choice of components
will dictate what a particular application does.

* We intend, over time, to make parts of this code base cross-platform via Kotlin Multiplatform, cross-compiling
Kotlin to JavaScript/TypeScript so that logic can be shared with frontend code. This is only the start of a
broader cross-platform goal. When writing code, ask "would this be useful in the frontend?" — for
example, schema parsing and validation that a client could run with the same rules as the server. If the answer
is yes, write it with multiplatform awareness: keep it in pure Kotlin over plain data (Map, List, primitives),
avoid JVM-only constructs (`java.*`, reflection, `java.util.Date`, atomics, and similar) that would not
cross-compile, and isolate any platform-specific dependencies away from the shareable logic. The schema parser
and validator are an early example written this way.

### Constants

* Generally, literal strings are to be avoided and string constants used instead. However, there are cases
where raw string constants are acceptable. If the string is used only once or is defined as a requirement by
a third party API and is limited to a single file, then the literal string can be used directly.

* Constants can start with a lower-case letter, and we will tend to avoid all upper case constant names. 
We knowingly violate Kotlin style on this. Because a lowerCamelCase `const val` trips Kotlin's naming
inspection, every acronym Object that holds such constants is annotated with `@Suppress("ConstPropertyName")`
at the object level (one annotation covers all the constants inside it) — see the objects in
`ExceptionConstants.kt` (`EXC`, `SRC`, `ACT`) and `LogSetup` for the pattern. Do not suppress per-constant.
If the constant is a string that is a key that is part of a schema, such as in a JSON schema, the name of the 
string should match its value.

* Constant declarations will always be wrappered by a Kotlin Object whose name is an acronym of 
the topic to which the constants apply. If the string constants are used across more than one class, they will
typically be put into a separate Kotlin file that ends with the suffix `Constants.kt` and the Objects will
not be wrappered by another class or object. The acronym name will have at most five characters and will
tend to be shorter for constants that are visible in more parts of the code. An exception is made for
constants that are essentially private to a class. Those can be wrappered by a single upper case letter.

* The acronym Object name is always upper-cased, and the constant is always referenced through it in a fully
qualified form, such as `EXC.badInput`. We do not statically or wildcard import the constants so that they
can be used bare. The upper-cased acronym prefix at the point of use is what signals that the value is a
constant, taking over the role that all upper case constant names would normally play.

### Enums

We lean away from Kotlin `enum` types for values that are part of the dynamically defined model — schema
choice values, configuration choices, and similar — because those are meant to be modifiable at runtime and
are better represented as the string constants described above. There is no hard rule, but we do reach for an
`enum` in specific cases:

* When an attribute has a strict, closed list of possible values and those values carry operational or state
meaning, an `enum` is appropriate. The compile-time enforcement is a feature there rather than a hindrance.

* We almost always use an `enum` for internally defined error or failure codes — a fixed set known up front
(for example, the data-validation failure codes). Note that `KdrException` deliberately does **not** follow
this rule: its `code`, `source`, and `activity` values are intentionally free-form and not confined to a known
list, so they remain plain values rather than an enum.

* State machines — such as job states or two-phase / transaction states — are a good fit for an `enum`. Unlike
the validation codes, these enums tend to get serialized. When an enum is serialized, treat its set of values
as part of a data contract that must be evolved and migrated carefully.

Enum entries follow the same lower-case-first naming as our constants (for example `ExpectedVal.array`,
`SchFailCode.missingRequired`, `LogLevel.debug`), rather than the Kotlin-conventional upper case. This trips
the enum-entry naming inspection, so the enum is annotated with `@Suppress("EnumEntryName")` at the enum-class
level. The one exception mirrors the schema-key rule above: when an entry name deliberately matches an external
or standard token, spell it as that token — for instance `HttpMethod.GET`/`POST`/`PUT`, which need no
suppression because they are already upper case.

### Extension Methods

Use them any place they might make sense, and if they are likely to be commonly used, then try to keep the method
names relatively short.

### Naming and Disambiguation

* We namespace with Kotlin packages, not with a prefix on every type. This is a deliberate break from the
prior-art `Dn`-everything convention. A type name should be as short and plain as it can be without becoming
ambiguous.

* Add a disambiguating prefix or suffix only when the bare name is a common, collision-prone word — one that
appears widely across the code and would clash with the standard library, third-party libraries, or the problem
domain. `Context`/`Cxt`, `Exception`, `Request`, `Response`, `Session`, `Type`, `Field`, and `Config` are the
usual offenders. Compound or specific names — `UserProfile`, `SchemaStore`, `InstanceConfig` — carry no "which
one?" ambiguity, so a prefix on them is just noise; leave them bare. Done right, the prefix or suffix shows up
precisely where ambiguity would otherwise arise, which makes it self-justifying rather than ceremonial.

* When a type does need disambiguating, prefer whatever reads best. A distinctive suffix can carry the load on
its own: `RequestCxt` needs no prefix because `Cxt` already sets it apart. A subsystem may use its own short
prefix for the family of types it owns — the schema layer uses `Sch` (`SchType`, `SchProperty`).

* The `Kdr` prefix is the always-available fallback. Use it for the core, cross-cutting runtime types when
nothing more specific fits — `KdrCxt`, `KdrException`, `KdrRequest`, `KdrResponse`. If you cannot find a better
disambiguator and the bare name would be ambiguous, `Kdr` is there.

### Schema

We will be defining JSON schema for anything where such a choice might make sense. Not only will we define
schema for endpoints, we will define schema for all configuration files as well. And these configuration files
may be fairly complex defining client accounts or data processing rules. Note, in some cases, we will
write functions in Kotlin to dynamically generate schema and configuration from patterns. When this
is fully mature, we should have two levels of schema and configuration in some places. The higher level
will be more conceptual and simpler, the schema it derives will be more detailed and allow for complete specifity.

Schema will be used to completely define endpoints, including the HTTP method and relative context path. It
will also dictate general security rules for access. The root context path for the endpoints will be 
dictated by environment variable configuration.

We will create Kotlin configuration builders for both the JSON schema and for the configuration data that
is written against the schemas. The builders will tend not to be complete, allowing some adhoc extra options
that are put into a free form Map instead of defined as a variable in a Kotlin class.

One of the early projects for this code is to create a JSON schema parser from scratch. This parser will be followed,
in some cases, by a linking and augmentation step where additional JSON schema is generated and linkage
between schemas is validated. This second phase execution will be an evaluation of all the relevantly defined 
JSON schema as a whole. The same will be true for complex application configuration data.

Besides using "$ref" constructs to do linkage, we will also allow overrides where recursive map merges can
modify either schema or configuration.

### Boilerplate: point-specific control, centralized definitions

This code base is deliberately heavy on boilerplate, and we accept that. It is a consequence of the other
decisions above rather than an accident. We want *point-specific customization* — the ability to adapt a
schema, a log line, an error message, or the exact way a value is serialized, right at the point where it
applies, with explicit code rather than reflection or framework magic (see the reflection and Kotlin-everywhere
rules). We also intend to aggressively capture redundancies in both definitions and behavior, and eventually to
write code that generates whole suites of endpoints and schema from higher-level rules of what is wanted.
Explicit, uniform boilerplate is exactly what such generators emit and manipulate, so we treat the verbosity as
a feature to be managed, not a smell to be hidden behind cleverness.

Since we are embracing this style, we pay down its main cost — scattering — by **centralizing the boilerplate
for a given type onto (or beside) the class that owns the data**. A type's schema-type definition, its
serialization, and the string constants for its field names all live together in one file, rather than being
spread across every place that consumes the type. The payoff is concrete: when you add or rename an attribute,
you edit one file, and any misalignment between the schema, the serialization, and the constants is visible in
that same file instead of drifting silently across the code base. We learned this the hard way on the prior
(dynamic runtime) project, where the definition, the serialization map, and the field keys for one concept were
scattered across many files and steadily fell out of sync; co-locating them removed most of that pain.

Concretely, a data class hosts its own serialization — implementing a small shared interface (e.g.
`JsonMappable` with `toJsonMap()`) so response handling can be written generically — and a companion method that
defines its schema type and owns that type's name, with its field-name constants declared right beside it.
Consumers call `x.toJsonMap()` and `TheType.defineSchema(builder)` rather than re-implementing the mapping or
re-declaring the fields elsewhere. `KdrEndpoint` (with its `toJsonMap`, `defineInfoType`, and the `EI` field
constants) is the reference example.

### Universal Exception

We will use a richly defined Exception class named "KdrException" which will have an attribute to
specify the HTTP code that should be sent back to a REST call. It defaults to `500`. Generally, we will
see exceptions in the lens of the HTTP codes they are likely to generate. The exception class will
also have a `source` attribute indicating in generic terms where the issue showed up. The source code
will **not** define any other exception classes.

In general, we will groom our error handling so that if you look at the full stack or errors, you should
get a good sense of where the error occurred and precisely the source of the issue. In some cases, errors
will be caught, wrappered, and rethrown just so additional information can be injected in the error stack.

### Universal Context

We will define a context object whose class is named `KdrCxt`. We will use this as an alternative to scoped
variables to pass context down a call stack. From this object you can retrieve current application configuration,
information about the endpoint that initiated the activity, if such occurred, user information about any user
that has been authenticated on the current executing thread, and so on. The `KdrCxt` will have map objects
where a caller can do free form association to provide additional context to an implementer further
down the call stack. All contexts will be named and have a context parent path. Sub context objects
can be created and will incorporate the parent context paths into its parent path. This path is used in logging
and debug. A context cannot be directly handed off to another thread, instead a sub context is generated
and given to the thread, and the sub-context will clone data from the parent context to avoid cross-thread context
pollution.

At a certain point, a `KdrCxt` may get bound to a particular client account. If that potentially disagrees with
the account associated with the acting user, then a sub-context should be generated and the desired
account explicitly assigned. This is relevant for users with admin rights to edit data across client accounts.
It is also relevant to background jobs that also can have cross-account scope. Once an account is assigned
to a `KdrCxt` cached data specific to the account can be added to the context. For example, there may be
complex rule sets particular to a client, and those can now be made conveniently available from the context.