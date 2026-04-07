# PRD — [Project Name]

<!--
  Copy this file to PRD.md and fill it out.
  This is the only file you configure per project.
  The loop reads it fresh on every iteration.

  Be specific. Vague PRDs produce vague software.
  The agent will use this to make every judgment call it encounters.
-->

---

## What this project is

<!--
  Describe the software in plain terms. What does it do? Who uses it?
  Include enough context that someone unfamiliar with the project
  could pick up a spec and implement it correctly.

  Example:
  "A REST API for a B2B SaaS platform serving financial services firms.
  It handles authentication, client data management, and webhook delivery.
  Written in TypeScript with Express. Postgres for persistence via Prisma ORM."
-->

[DESCRIBE THE PROJECT]

---

## Tech stack

<!--
  List the languages, frameworks, and tools in use.
  Be explicit — the agent should not guess.

  Example:
  - Language: TypeScript 5.x
  - Runtime: Node.js 20
  - Framework: Express 4
  - ORM: Prisma
  - Database: PostgreSQL 16
  - Testing: Vitest
  - Linting: ESLint + Prettier
  - Package manager: pnpm
-->

- Language:
- Runtime / Framework:
- Testing:
- Linting / formatting:
- Package manager:
- Other tools:

---

## How to run the project

<!--
  Exact commands to start the project locally.
  The agent will run these to verify changes work.

  Example:
  ```bash
  pnpm install
  pnpm dev
  ```
-->

```bash
# Install dependencies
[COMMAND]

# Run the project
[COMMAND]
```

---

## How to run tests

<!--
  CRITICAL. This is how the agent verifies its work.
  The agent runs these after every spec and reads the output.
  Be exact.

  Example:
  ```bash
  pnpm test           # run all tests
  pnpm test --run     # run once (no watch mode)
  pnpm test:coverage  # coverage report
  ```
-->

```bash
# Run all tests
[COMMAND]

# Run a single test file
[COMMAND]
```

Expected output when all tests pass:
```
[PASTE WHAT PASSING OUTPUT LOOKS LIKE]
```

---

## How to lint / type-check

<!--
  The agent should run these before marking a spec done.

  Example:
  ```bash
  pnpm lint
  pnpm typecheck
  ```
-->

```bash
[COMMAND]
```

---

## Coding conventions

<!--
  Things the agent must match. Be opinionated.
  If you have a style guide or existing patterns, describe them here.

  Example:
  - Use named exports, not default exports
  - All async functions must handle errors explicitly — no unhandled promise rejections
  - Database queries live in /src/db/ — not inline in routes
  - Route handlers are thin — logic lives in service functions
  - Test files live next to source files: foo.ts → foo.test.ts
  - No any types — use unknown and narrow
-->

[LIST YOUR CONVENTIONS]

---

## Repository structure

<!--
  Describe the directory layout so the agent knows where things live.

  Example:
  ```
  /src
    /routes       HTTP route handlers
    /services     Business logic
    /db           Database queries and migrations
    /middleware   Express middleware
  /tests          Integration tests
  /prisma         Schema and migrations
  ```
-->

```
[PASTE YOUR DIRECTORY TREE]
```

---

## Definition of "done" for a spec

<!--
  The agent uses this to decide when to move a spec to specs/done/.
  The stricter this is, the higher the output quality.

  Recommended defaults (edit to taste):
-->

A spec is done when ALL of the following are true:

- [ ] The feature described in the spec is implemented
- [ ] All existing tests pass (`[YOUR TEST COMMAND]`)
- [ ] New tests exist for the new behavior (unless the spec says otherwise)
- [ ] Linting passes (`[YOUR LINT COMMAND]`)
- [ ] Type checking passes (`[YOUR TYPECHECK COMMAND]`)
- [ ] No debug code or console.log statements left in
- [ ] progress.txt is updated with verification results

---

## Constraints and guardrails

<!--
  Things the agent must never do in this project.
  Be explicit about anything where a wrong move would be costly.

  Example:
  - Never modify the database schema directly — use Prisma migrations
  - Never commit secrets or credentials
  - Never change the public API shape of existing endpoints without a spec that explicitly calls for it
  - The /admin routes require a specific auth pattern — see src/middleware/adminAuth.ts before touching anything in /routes/admin/
-->

[LIST YOUR HARD CONSTRAINTS]

---

## Environment

<!--
  What environment variables or config does the project need?
  The agent should know what's available without having to guess.

  Example:
  DATABASE_URL      set in .env — Postgres connection string
  JWT_SECRET        set in .env
  NODE_ENV          set by the test runner automatically

  Do not include actual values here.
-->

[LIST ENV VARS AND WHERE THEY COME FROM]
