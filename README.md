# MiraBounties

MiraBounties is the player-bounty system for the Mira Paper server suite. Players can place Vault-backed bounties on other players, stack contributions on the same target, view the richest targets, and automatically pay the bounty to a legitimate killer.

## Download

[**Download MiraBounties v0.1.0**](https://github.com/FiveSOCE/Mira-Bounties/releases/download/v0.1.0/MiraBounties-0.1.0.jar)

## Requirements / Dependencies

- Paper 1.21.11
- Java 21
- Vault
- A Vault-compatible economy provider
- PlaceholderAPI optional
- MiraCore optional integration
- MiraCombat optional integration
- MiraLeaderboards optional integration
- MiraNPC optional integration

## How MiraBounties Works

Bounties are persistent money rewards attached to player targets. Multiple players can contribute to the same bounty, with configurable minimum/maximum contribution limits and optional contribution expiry/refunds. When a valid player kill occurs, the accumulated bounty is paid to the killer and removed from the target. High-value posts and claims can be broadcast server-wide. Bounty data is stored in `plugins/MiraBounties/bounties.yml`.

PlaceholderAPI exposes the current player's bounty and Top 10 bounty rankings, allowing the data to be displayed through MiraNPC or other leaderboard displays.

## Commands

| Command | Permission | What it does |
| --- | --- | --- |
| `/bounty` | `mirabounties.use` | Opens/views normal bounty information. |
| `/bounty <player>` | `mirabounties.use` | Shows the selected player's current bounty. |
| `/bounty <player> <amount>` | `mirabounties.post` | Adds the specified amount to that player's bounty. |
| `/bounty top` | `mirabounties.use` | Displays the highest active bounties. |
| `/bounty admin set <player> <amount>` | `mirabounties.admin` | Force-sets a player's bounty value. |
| `/bounty admin clear <player>` | `mirabounties.admin` | Clears a player's active bounty. |

Aliases: `/bounties`

## Permissions

| Permission | Default | What it does |
| --- | --- | --- |
| `mirabounties.use` | Everyone | Allows normal bounty viewing and use. |
| `mirabounties.post` | Everyone | Allows posting/contributing money to bounties. |
| `mirabounties.admin` | OP | Allows administrative bounty management. |
