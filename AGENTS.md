# DataPilot development rules

- Read `docs/implementation-plan.md` before starting or extending a development stage.
- Stay within the stage the user authorized. Report stage progress only after its recorded checks pass.
- Never commit API keys, database passwords, `.env` files, customer data, or fabricated query results.
- Mark generated business data synthetic. Use the documented metric definitions and evidence from executed queries.
- Never execute a model-produced query until the server-side SQL AST allowlist and read-only authorization have passed.
- Keep migrations and data seeding credentials separate from the read-only application account.
- For changes involving a framework or dependency API, use Context7 to check the current official docs first.
- Use the repository PowerShell commands and Maven Wrapper. Do not change global Git configuration.
- After each requested stage, run its documented tests and builds, fix failures, and update the implementation plan.
