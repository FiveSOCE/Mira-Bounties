# MiraBounties

Standalone player bounty hunting for the Mira Paper 1.21.11 / Java 21 plugin suite.

## Features

- Vault-backed player bounties
- multiple bounty contributions stack on one target
- configurable minimum and maximum post values
- configurable contribution expiry with optional refunds
- automatic bounty payout on legitimate player kill
- high-value post and claim broadcasts
- `/bounty <player>`
- `/bounty <player> <amount>`
- `/bounty top`
- `/bounty admin set <player> <amount>`
- `/bounty admin clear <player>`
- PlaceholderAPI leaderboard placeholders
- public `MiraBountiesApi` through Bukkit ServicesManager

## PlaceholderAPI

```text
%mirabounties_bounty%
%mirabounties_bounty_formatted%
%mirabounties_top_1_name%
%mirabounties_top_1_value%
%mirabounties_top_1_formatted%
```

Top placeholders support ranks 1 through 10 and work without player context, making them suitable for MiraNPC and leaderboard displays.

## Data

Persistent bounty contributions are stored in:

```text
plugins/MiraBounties/bounties.yml
```

## Requirements

- Paper 1.21.11
- Java 21
- Vault
- Vault-compatible economy provider
- PlaceholderAPI optional

## Building

```bash
gradle clean build
```

Output:

```text
build/libs/MiraBounties-0.1.0.jar
```

The verified release download is added here after CI/release verification.
