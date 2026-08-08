# Contributing to Master2

Thank you for your interest in contributing to Master2! This document provides guidelines for contributing.

## Code of Conduct

Please be respectful and constructive in all interactions.

## How to Contribute

### Reporting Bugs

1. Check if the issue already exists
2. Create a new issue with:
   - Clear title
   - Steps to reproduce
   - Expected vs actual behavior
   - Device info and Android version
   - Relevant logs

### Suggesting Features

1. Open an issue with `[Feature Request]` prefix
2. Describe the feature and use case
3. Explain why it would benefit users

### Pull Requests

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/my-feature`
3. Follow code style guidelines
4. Write meaningful commit messages
5. Test your changes thoroughly
6. Submit PR with clear description

## Code Style

### Java

- Use 4 spaces for indentation
- Follow Android naming conventions
- Add comments for complex logic
- Keep methods under 50 lines when possible

### Resources

- Use descriptive names: `activity_parent_dashboard.xml`
- Extract strings to `strings.xml`
- Use dimensions from `dimens.xml`

## Project Structure

```
app/src/main/java/com/example/master2/
├── adapters/     # Keep adapter classes here
├── models/       # Data models only
├── services/     # Background services
├── utils/        # Utility classes
└── [Activities]  # Main package for activities
```

## Testing

- Write unit tests for business logic
- Write UI tests for critical flows
- Run all tests before submitting PR:
  ```bash
  ./gradlew test
  ./gradlew connectedAndroidTest
  ```

## Documentation

- Update README.md for new features
- Add JSDoc comments for public methods
- Update docs/ for architectural changes

## Questions?

Open an issue with `[Question]` prefix.
