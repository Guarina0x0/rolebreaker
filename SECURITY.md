# Security Policy

## Reporting a vulnerability

If you find a security issue in RoleBreaker itself, please report it privately:

- Use GitHub's **private vulnerability reporting**: go to the **Security** tab →
  **Report a vulnerability**.
- Or open a regular [Issue](../../issues) for non-sensitive bugs.

Please do not disclose a security-relevant bug publicly until it has been addressed.
I'll try to acknowledge reports within a few days.

## Supported versions

This is an early-stage project; only the latest commit on the `public` branch is
supported. Please make sure you're running the newest build before reporting.

## Responsible use

RoleBreaker is a **security testing tool** intended for authorized assessments only —
penetration tests, bug bounty programs within scope, CTFs, and testing systems you own
or have explicit written permission to test.

- It replays HTTP requests with swapped tokens and can send forged JWTs. Only point it
  at targets you are authorized to test.
- It never validates signatures and never sends expired/blank tokens, but it will send
  the tokens you provide — treat captured tokens as sensitive.
- You are responsible for complying with all applicable laws and program rules.

Using this tool against systems without authorization may be illegal.
