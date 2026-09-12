# Deployment Feature

## Overview

Deployment provides package, run, generate Dockerfile, build/run/push image, compose up, build installer with jpackage, copy to server via SCP, and check Spring Boot Actuator health.

## Component Structure

```
plugins/smide-plugin-java/src/main/java/com/smide/plugin/java/deploy/
├── DeployToolWindow.java             # Deploy tool window
├── DeployManager.java                # Manages deployment operations
├── PackageAction.java                # Package action
├── RunAction.java                    # Run action
├── DockerfileGenerator.java          # Generates Dockerfiles
├── DockerBuilder.java                # Builds Docker images
├── DockerRunner.java                 # Runs Docker containers
├── DockerPusher.java                 # Pushes Docker images
├── ComposeRunner.java                # Runs docker-compose
├── JpackageBuilder.java              # Builds jpackage installer
├── ScpCopier.java                    # Copies files via SCP
├── HealthChecker.java                # Checks Spring Boot health
└── DeployConfig.java                 # Deployment configuration
```

## Key Classes

### DeployManager
- Coordinates deployment operations
- Provides API for all deployment actions
- Manages deployment configuration

### DockerfileGenerator
- Generates multi-stage Dockerfiles
- Customizes based on project type
- Provides template for customization

### ScpCopier
- Copies files to remote server via SCP
- Handles authentication
- Provides progress feedback

## Data Flow

### Docker Deployment Flow
```
User clicks "Generate Dockerfile"
    ↓
DockerfileGenerator creates Dockerfile
    ↓
User reviews and saves Dockerfile
    ↓
User clicks "Build Image"
    ↓
DockerBuilder runs docker build
    ↓
User clicks "Run Container"
    ↓
DockerRunner runs docker run
    ↓
User clicks "Push Image"
    ↓
DockerPusher runs docker push
```

### Server Deployment Flow
```
User clicks "Copy to Server"
    ↓
ScpCopier connects to server
    ↓
ScpCopier uploads artifact
    ↓
ScpCopier runs restart command
    ↓
User clicks "Check Health"
    ↓
HealthChecker calls Spring Boot Actuator
    ↓
Health status is displayed
```

## Related Features

### Java Support
Deployment is tightly coupled with Java:
- **Package**: Package JAR/WAR files
- **Docker**: Generate Dockerfiles for Java applications
- **Spring Boot**: Check Spring Boot Actuator health
- **jpackage**: Build installers for Java applications

**Key Integration Point**: `DeployManager` → Java build artifacts

### Terminal
Deployment uses terminal:
- **Docker Commands**: Run Docker commands
- **SCP Commands**: Copy files to remote servers
- **Custom Scripts**: Run deployment scripts

**Key Integration Point**: `DeployManager` ↔ `TerminalService`

### Git Integration
Deployment integrates with Git:
- **Version Control**: Track deployment configurations
- **Branch Management**: Deploy from specific branches
- **Commit History**: Find deployment history

**Key Integration Point**: `DeployManager` → Git service

### Plugin System
Deployment is a plugin:
- **Deploy Tool Window**: Provides deployment UI
- **Deployment Actions**: Add custom deployment actions
- **Configuration**: Add custom deployment configuration

**Key Integration Point**: `DeployManager` → Plugin deployment actions

### Debugging
Deployment integrates with debugging:
- **Run Configuration**: Use run configurations for deployment
- **Debug Remote**: Debug deployed applications
- **Health Check**: Check health before debugging

**Key Integration Point**: `DeployManager` ↔ `DebuggerService`

## Extension Points

### For Plugin Developers

1. **Deployment Actions**: Add custom deployment actions
2. **Configuration**: Add custom deployment configuration
3. **Providers**: Add custom deployment providers (e.g., Kubernetes)

## How to Add a New Deployment Target

1. Create a new action class (e.g., `KubernetesDeployAction`)
2. Implement deployment logic
3. Register in DeployToolWindow
4. Test by deploying to the target

## Known Limitations

- Docker operations require Docker installation
- SCP requires SSH access to server
- Health checking is Spring Boot specific
- No rollback mechanism in v1

## See Also

- [Java Support](feature-java.md) - Java deployment
- [Terminal](feature-terminal.md) - Terminal integration
- [Git Integration](feature-git.md) - Git integration
- [Plugin System](feature-plugins.md) - Deployment plugin
