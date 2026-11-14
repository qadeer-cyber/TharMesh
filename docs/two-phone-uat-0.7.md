# Two-phone UAT checklist (Update 0.7)

Category 7 (physical E2E): **NOT EXECUTED** on this cloud agent (no paired devices).

Use this checklist on owner hardware after staging is live:

1. Google signup on phone A
2. Email OTP on isolated test account on phone B
3. Offline relaunch on A
4. Local encrypted messaging A↔B
5. Same-account login on B
6. Enrollment approved on A
7. Device directory shows both
8. Revoke B from A
9. B cannot perform sensitive cloud actions
10. Recovery rotation
11. Protected recovery path
12. Identity + chats preserved
13. Offline BT/Wi-Fi mesh messaging
14. Upgrade install without data loss

Record device models, APK SHA-256, and sanitized logs per step.
