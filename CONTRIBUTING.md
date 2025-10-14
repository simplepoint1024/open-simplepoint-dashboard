Here's a concise and relevant `CONTRIBUTING.md` tailored to your project's characteristics:

# Contributing Guidelines

Thank you for your interest in contributing to `simplepoint-main`. Please follow these guidelines to ensure smooth
collaboration.

## Prerequisites

- Java (JDK 17 or later)
- Kotlin
- Gradle
- Node.js (for JavaScript/TypeScript)

## Development Setup

1. Clone the repository:

```shell
git clone git@github.com:simplepoint1024/simplepoint-main.git
cd simplepoint-main
```

2. Build the project using Gradle:

```shell
./gradlew build
```

3. Run the Spring Boot application:

```shell
./gradlew bootRun
```

## Branching Strategy

- Fork the repository and create your branch from `master`.
- Name your branch clearly, e.g., `feature/your-feature-name` or `fix/issue-description`.

## Commit Messages

Use clear and concise commit messages:

```
type(scope): short description

Example:
feat(auth): add JWT authentication support
fix(ui): resolve button alignment issue
```

## Pull Requests

- Submit pull requests to the `master` branch.
- Clearly describe your changes and reference related issues.
- Ensure all tests pass and code is formatted consistently.

## Code Style

- Follow standard Java/Kotlin conventions.
- Use ESLint/Prettier for JavaScript/TypeScript code formatting.
- Keep code clean, readable, and maintainable.

## Testing

- Write unit and integration tests for new features.
- Ensure existing tests pass before submitting your PR:

```shell
./gradlew test
```

## Reporting Issues

- Clearly describe the issue, steps to reproduce, and expected behavior.
- Include relevant logs, screenshots, or code snippets.

Thank you for contributing!