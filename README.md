# MiraBounties

MiraBounties is the player-bounty system for the Mira Paper server suite. Players can place Vault-backed bounties on other players, stack contributions on the same target, view the richest targets, and automatically pay the bounty to a legitimate killer.

## Download

[**Download MiraBounties v0.1.1**](https://github.com/FiveSOCE/Mira-Bounties/releases/download/v0.1.1/MiraBounties-0.1.1.jar)

## Requirements / Dependencies

- Paper 1.21.11
- Java 21
- Vault
- A Vault-compatible economy provider
- PlaceholderAPI optional
- MiraCore 0.2.0 or newer
- MiraLeaderboards optional integration
- MiraNPC optional integration

## How MiraBounties Works

Bounties are persistent money rewards attached to player targets. Multiple players can contribute to the same bounty, with configurable minimum/maximum contribution limits and optional contribution expiry/refunds. When a valid player kill occurs, the accumulated bounty is paid to the killer and removed from the target. High-value posts and claims can be broadcast server-wide. Bounty data is stored in `plugins/MiraBounties/bounties.yml`.

v0.1.1 makes MiraBounties the single bounty authority for the Mira suite. Claims are written to persistent history, hunters accumulate claim counts and money-claimed totals, and `/bounty hunters` plus `/bounty history [player]` expose that data directly. MiraCore records bounty posts/claims/admin changes in the audit trail and awards first-claim/high-value-claim milestones. Expired contribution refunds are now retained for retry if the economy refund fails instead of silently discarding money.

PlaceholderAPI exposes player bounty/hunter stats, active Top 10 bounty rankings, Top 10 bounty hunters, global posted/claimed totals and the latest claim. The public API exposes active bounty rankings plus hunter claim totals for MiraLeaderboards/MiraNPC integration.

## Commands

| Command | Permission | What it does |
| --- | --- | --- |
| `/bounty` | `mirabounties.use` | Opens/views normal bounty information. |
| `/bounty <player>` | `mirabounties.use` | Shows the selected player's current bounty. |
| `/bounty <player> <amount>` | `mirabounties.post` | Adds the specified amount to that player's bounty. |
| `/bounty top` | `mirabounties.use` | Displays the highest active bounties. |
| `/bounty hunters` | `mirabounties.use` | Displays the top bounty hunters by total money claimed. |
| `/bounty history [player]` | `mirabounties.use` | Displays recent bounty claims globally or filtered to one player. |
| `/bounty admin set <player> <amount>` | `mirabounties.admin` | Force-sets a player's bounty value. |
| `/bounty admin clear <player>` | `mirabounties.admin` | Clears a player's active bounty. |

Aliases: `/bounties`

## Permissions

| Permission | Default | What it does |
| --- | --- | --- |
| `mirabounties.use` | Everyone | Allows normal bounty viewing and use. |
| `mirabounties.post` | Everyone | Allows posting/contributing money to bounties. |
| `mirabounties.admin` | OP | Allows administrative bounty management. |


## PlaceholderAPI

Player-context:

- `%mirabounties_bounty%`
- `%mirabounties_bounty_formatted%`
- `%mirabounties_claims%`
- `%mirabounties_claimed_total%`
- `%mirabounties_claimed_total_formatted%`

Ranked/global:

- `%mirabounties_top_1_name%` / `value` / `formatted` through rank 10
- `%mirabounties_hunter_top_1_name%` / `value` / `formatted` through rank 10
- `%mirabounties_total_posted%`
- `%mirabounties_total_claimed%`
- `%mirabounties_last_claim_killer%`
- `%mirabounties_last_claim_victim%`
- `%mirabounties_last_claim_value%`
- `%mirabounties_last_claim_formatted%`
