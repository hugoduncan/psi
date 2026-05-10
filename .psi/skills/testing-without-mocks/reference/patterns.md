# Testing Without Mocks — Pattern Details & Examples

Reference for James Shore's pattern language.
Source: https://www.jamesshore.com/v2/projects/nullables/testing-without-mocks

## Table of Contents

1. [Foundational Patterns](#foundational-patterns)
2. [Architectural Patterns](#architectural-patterns)
3. [Logic Patterns](#logic-patterns)
4. [Infrastructure Patterns](#infrastructure-patterns)
5. [Nullability Patterns](#nullability-patterns)
6. [Legacy Code Patterns](#legacy-code-patterns)
7. [Language-Specific Examples](#language-specific-examples)

---

## Foundational Patterns

### Narrow Tests

Use narrow (unit-level) tests focused on one concept each. Do not rely on broad end-to-end tests. Each class/module has its own test file that tests its own behavior. Dependencies are exercised through sociable tests, not replaced with mocks.

### State-Based Tests

Assert on *outputs and observable state*, never on which methods were called on dependencies.

```typescript
// GOOD: state-based — checks output
const result = formatDate(new Date(2024, 0, 15));
assert.equal(result, "January 15, 2024");

// BAD: interaction-based — checks how it was done
verify(formatter).wasCalledWith("MMMM d, yyyy");
```

### Overlapping Sociable Tests

Let tests execute real dependency code. When testing class A that depends on B, let A's tests run B's real code. B also has its own tests. This creates overlapping coverage — if a bug is introduced in B, both A's and B's tests may fail. The tradeoff is worth it: tests remain realistic and refactoring-safe.

The overlap is the key safety mechanism. A's tests ensure A uses B correctly in context. B's tests ensure B works in isolation. Together they cover the integration without a separate integration test.

### Smoke Tests

Write one or two end-to-end tests that start the system and run a common happy-path workflow. These are a safety net only. If a smoke test catches something the narrow tests don't, add a narrow test to fill the gap.

### Zero-Impact Instantiation

Constructors must not perform I/O, connect to external systems, or do heavy computation. Use a separate `create()` or `connect()` async factory for initialization. This ensures objects can be instantiated freely in tests without side effects.

```typescript
// GOOD: constructor does nothing heavy
class DbClient {
  private connection: Connection | null = null;
  constructor(private config: DbConfig) {}

  static async create(config: DbConfig): Promise<DbClient> {
    const client = new DbClient(config);
    client.connection = await connect(config);
    return client;
  }
}
```

### Parameterless Instantiation

Provide factory methods with sensible defaults so tests can create instances without specifying every parameter. This keeps tests concise and resilient to constructor signature changes.

```typescript
class UserService {
  static create(db: Database, cache: Cache) { return new UserService(db, cache); }

  // Nullable factory — no parameters required
  static createNull() {
    return new UserService(Database.createNull(), Cache.createNull());
  }
}
```

### Signature Shielding

Use options/config objects instead of long parameter lists. When parameters are added, existing call sites (and tests) do not break.

```typescript
// GOOD: options object
interface LoginOptions {
  client?: LoginClient;
  maxRetries?: number;
  timeout?: number;
}
class LoginController {
  static create(options: LoginOptions = {}) { /* ... */ }
}

// BAD: positional params break callers when changed
class LoginController {
  static create(client: LoginClient, maxRetries: number, timeout: number) { /* ... */ }
}
```

---

## Architectural Patterns

### A-Frame Architecture

Structure the application so that Infrastructure and Logic are *peers* under an Application layer. Infrastructure and Logic must NOT depend on each other.

```
        Application / UI
       /              \
  Infrastructure      Logic
       |                |
  (external systems)  Values
```

- **Application layer** — orchestrates; depends on both Infrastructure and Logic
- **Logic layer** — pure computation; depends only on Values; no infrastructure imports
- **Infrastructure layer** — wraps external systems; one wrapper per system
- **Values layer** — immutable data objects passed between layers

### Logic Sandwich

The simplest Application-layer pattern. A method does three things in order:

1. **Read** from infrastructure (get data)
2. **Compute** with logic (process it)
3. **Write** to infrastructure (store/send results)

```typescript
// Application layer — Logic Sandwich
async handleRequest(request: Request): Promise<Response> {
  // 1. Read
  const userData = await this.db.getUser(request.userId);
  // 2. Compute
  const response = this.formatter.formatUserProfile(userData);
  // 3. Write
  await this.logger.log("profile_viewed", request.userId);
  return response;
}
```

This is testable because you can Null the infrastructure and check the output.

### Traffic Cop

When logic and infrastructure must interleave (not a clean sandwich), use an event-driven or callback approach. The Traffic Cop itself should be very thin — just routing, no logic. Test it with Nullables.

### Grow Evolutionary Seeds

Start with a tiny walking skeleton that does one thing end-to-end. Grow the architecture incrementally. Don't try to design the full A-Frame up front.

---

## Logic Patterns

### Easily-Visible Behavior

Make computation results observable:

- **Pure functions** — output determined entirely by inputs (best)
- **Immutable objects** — state set at construction, never changes
- **Mutable objects with getters/events** — expose state changes so tests can observe them

Avoid reaching more than one level deep into dependency state. If you need `a.b.c`, the design needs improvement.

### Testable Libraries

When a third-party library is hard to test (side effects, complex API, no Nullable support), wrap it:

```typescript
// Wrap the hard-to-test library
class MarkdownRenderer {
  render(text: string): string {
    return thirdPartyLib.render(text); // thin wrapper
  }
}
```

Now you can make `MarkdownRenderer` Nullable if needed, and your logic tests use the wrapper's clean API.

### Collaborator-Based Isolation

When logic class A depends on logic class B, do NOT mock B. Let A's tests run B's real code. Both A and B have their own tests. This is the sociable testing approach applied to logic. The overlapping coverage is intentional.

---

## Infrastructure Patterns

### Infrastructure Wrappers

Create one wrapper class per external system. The wrapper:

- Provides a clean, domain-relevant API to the rest of the codebase
- Hides all external system details (HTTP, SQL, file system specifics)
- Is the ONLY code that talks to that external system
- Keeps dependencies simple: avoid complex wrapper-to-wrapper dependency graphs

```typescript
class UserRepository {
  // Clean API — callers don't know about SQL
  async findById(id: UserId): Promise<User | null> { /* SQL hidden here */ }
  async save(user: User): Promise<void> { /* SQL hidden here */ }
}
```

### Narrow Integration Tests

Test infrastructure wrappers against the REAL external system. These tests are slow and need setup, but they are the only tests that touch real infrastructure.

- Test against a real database, real filesystem, real API
- Focus on your wrapper's behavior, not the external system's full API
- Keep them narrow: one wrapper per test file
- Tag them so they can be run separately from the fast unit tests

### Paranoic Telemetry

Some integration issues only surface in production. Monitor production behavior to detect:

- Unexpected response formats from third-party APIs
- Latency or timeout pattern changes
- Data consistency issues

This is a complement to testing, not a replacement.

---

## Nullability Patterns

These are the core innovation of the pattern language. They let you test code with infrastructure dependencies without mocks.

### Nullables

Every infrastructure wrapper (and classes that depend on them) gets a `createNull()` static factory method. The Null instance:

- Disables all external communication
- Behaves normally in every other respect (all logic runs)
- Is production code (not test-only) — useful for "dry run" modes, dev environments, etc.
- Supports Parameterless Instantiation

```typescript
class EmailClient {
  static create(config: SmtpConfig) {
    return new EmailClient(config, new SmtpTransport(config));
  }

  static createNull() {
    return new EmailClient(DEFAULT_CONFIG, new StubbedTransport());
  }

  async send(to: string, subject: string, body: string): Promise<void> {
    await this.transport.send({ to, subject, body });
  }
}
```

### Embedded Stub

The stub logic lives INSIDE the production class, not in a separate test file. This is typically implemented as an inner class or a flag that switches between real and stubbed behavior.

```typescript
class HttpClient {
  private transport: Transport;

  static create() {
    return new HttpClient(new RealTransport());
  }

  static createNull(responses: Map<string, string> = new Map()) {
    return new HttpClient(new StubbedTransport(responses));
  }

  async get(url: string): Promise<string> {
    return this.transport.get(url);
  }
}

// StubbedTransport is an INNER/co-located class, not in test code
class StubbedTransport implements Transport {
  constructor(private responses: Map<string, string>) {}
  async get(url: string): Promise<string> {
    return this.responses.get(url) ?? "";
  }
}
```

Key: the stub is maintained alongside the production code. When the production code changes, the stub is updated in the same commit.

### Thin Wrapper

When third-party code is too complex or entangled to stub directly, create a minimal wrapper whose only job is to call the third party. Then embed the stub in your wrapper:

```typescript
class ThirdPartyPayment {
  // Thin wrapper — just delegates
  async charge(amount: number, token: string): Promise<ChargeResult> {
    return externalSdk.charges.create({ amount, source: token });
  }

  static createNull(result: ChargeResult = { id: "null_ch", status: "succeeded" }) {
    const instance = new ThirdPartyPayment();
    instance.charge = async () => result; // embedded stub
    return instance;
  }
}
```

### Configurable Responses

`createNull()` accepts optional parameters to control what the Null instance returns. Define responses in terms of the wrapper's PUBLIC API, not its implementation details.

```typescript
// Responses are about the wrapper's domain, not HTTP details
const client = LoginClient.createNull({
  userInfo: { name: "Alice", role: "admin" },  // NOT: { statusCode: 200, body: '{"name":"Alice"}' }
});

// Test uses the configured response
const controller = LoginController.create({ client });
const result = await controller.handleLogin("alice_token");
assert.equal(result.greeting, "Welcome, Alice");
```

Use named and optional parameters so tests only configure what they care about.

### Output Tracking

Null instances record what WOULD have been sent to the external system. This allows state-based assertions on side effects.

```typescript
const email = EmailClient.createNull();
const tracker = email.trackOutput();

await orderService.processOrder(order);

// State-based assertion on the side effect
assert.deepEqual(tracker.data, [{
  to: "customer@example.com",
  subject: "Order Confirmation",
  body: expect.stringContaining("Order #1234")
}]);
```

Output Tracking turns interaction testing (spy/verify) into state-based testing (check a data structure). The tracker is a simple array that collects output records.

### Behavior Simulation

For complex, stateful infrastructure (e.g., a database that returns what was previously stored), the embedded stub simulates realistic behavior:

```typescript
class InMemoryUserStore {
  private data = new Map<string, User>();

  async save(user: User): Promise<void> { this.data.set(user.id, user); }
  async findById(id: string): Promise<User | null> { return this.data.get(id) ?? null; }
}

class UserRepository {
  static createNull() {
    return new UserRepository(new InMemoryUserStore());
  }
}
```

### Fake It Once You Make It

When converting legacy code, you cannot make every dependency Nullable at once. Instead:

1. Make the direct dependencies of the code you're testing Nullable
2. For dependencies further down the tree that aren't yet Nullable, the createNull() of your direct dependency handles it (it stubs out its own infra)
3. Work your way down the tree incrementally

---

## Legacy Code Patterns

### Descend the Ladder

Convert one module and its direct dependencies at a time. Work DOWN the dependency tree:

1. Pick the module you want to test better
2. Make its direct dependencies Nullable (or use Throwaway Stubs for ones you can't convert yet)
3. Write proper narrow, sociable, state-based tests
4. Move on to the next layer of dependencies when time allows

### Climb the Ladder

Alternative: start from the LOWEST infrastructure wrappers and work UP:

1. Identify the lowest-level infrastructure wrappers
2. Write Narrow Integration Tests for them
3. Add `createNull()` with Embedded Stub
4. Move up to the next layer that depends on these wrappers

### Replace Mocks with Nullables

For existing mock-heavy tests:

1. Identify a mock in a test
2. Determine what real class it replaces
3. Add `createNull()` to that class
4. Replace the mock setup with `createNull()` + Configurable Responses
5. Replace `verify()` calls with Output Tracking assertions
6. The test now uses real code paths with disabled infrastructure

### Throwaway Stub

When you need to test a module but can't yet convert all its dependencies:

1. Create a simple, temporary stub for the unconverted dependency
2. Use it to unblock your current work
3. When you eventually make that dependency Nullable, delete the throwaway stub

---

## Language-Specific Examples

### TypeScript / JavaScript

```typescript
// Infrastructure Wrapper with Nullable
class FileStore {
  private fs: FileSystem;

  private constructor(fs: FileSystem) { this.fs = fs; }

  static create(): FileStore {
    return new FileStore(new RealFileSystem());
  }

  static createNull(files: Record<string, string> = {}): FileStore {
    return new FileStore(new StubbedFileSystem(files));
  }

  async read(path: string): Promise<string> { return this.fs.read(path); }
  async write(path: string, content: string): Promise<void> { this.fs.write(path, content); }

  trackWrites(): OutputTracker<{ path: string; content: string }> {
    return this.fs.trackWrites();
  }
}

// Test using Nullable
it("processes config file", async () => {
  const fileStore = FileStore.createNull({
    "/etc/app.conf": "key=value"
  });
  const tracker = fileStore.trackWrites();
  const app = App.create({ fileStore });

  await app.processConfig();

  assert.deepEqual(tracker.data, [
    { path: "/var/app/parsed.json", content: '{"key":"value"}' }
  ]);
});
```

### Python

```python
class EmailGateway:
    def __init__(self, transport):
        self._transport = transport

    @classmethod
    def create(cls, smtp_config):
        return cls(SmtpTransport(smtp_config))

    @classmethod
    def create_null(cls, **responses):
        return cls(StubbedTransport(**responses))

    def send(self, to, subject, body):
        self._transport.send(to=to, subject=subject, body=body)

    def track_output(self):
        return self._transport.track_output()


class StubbedTransport:
    def __init__(self):
        self._sent = []

    def send(self, **kwargs):
        self._sent.append(kwargs)

    def track_output(self):
        return self._sent


# Test
def test_order_confirmation_sends_email():
    email = EmailGateway.create_null()
    tracker = email.track_output()
    service = OrderService(email_gateway=email)

    service.confirm_order(order_id="123", customer_email="a@b.com")

    assert tracker == [{"to": "a@b.com", "subject": "Order #123 Confirmed", "body": ANY}]
```

### Java / Kotlin

```kotlin
class NotificationService private constructor(
    private val transport: Transport
) {
    companion object {
        fun create(config: Config) = NotificationService(HttpTransport(config))

        fun createNull(
            responses: Map<String, String> = emptyMap()
        ) = NotificationService(StubbedTransport(responses))
    }

    fun send(userId: String, message: String): SendResult {
        return transport.send(userId, message)
    }

    fun trackOutput(): OutputTracker = transport.trackOutput()
}
```

---

## Anti-Patterns to Avoid

| Anti-Pattern | Why It's Bad | Do This Instead |
|---|---|---|
| Mocking collaborators | Locks in implementation; breaks on refactor | Sociable tests with real collaborators |
| Interaction-based assertions (`verify()`) | Tests how, not what; fragile | State-based assertions on output/state |
| DI frameworks for testability | Magic; hidden complexity | Simple constructor injection + `createNull()` |
| Auto-mocking frameworks | Makes bad tests easy to write | Make good tests easy with Nullables |
| Test stubs in test files only | Stub drifts from production code | Embedded Stub in production class |
| Broad integration test suites | Slow, flaky, hard to maintain | Narrow sociable tests + smoke tests |
| Heavy constructor initialization | Makes instantiation in tests painful | Zero-Impact Instantiation + factory methods |
