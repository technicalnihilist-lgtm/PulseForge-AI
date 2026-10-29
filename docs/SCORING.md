# Scoring rules

Both scores start at 100 and subtract documented amounts. Every deduction is listed in the report,
so a reader can recompute the number by hand and disagree with a specific line rather than with a
mystery.

## Security score

```
score = clamp(100 - Σ points, 0, 100)
points(finding) = round(weight(severity) × multiplier(confidence))
```

| Severity | Weight |
|---|---|
| Critical | 25 |
| High | 12 |
| Medium | 5 |
| Low | 2 |
| Info | 0 |

| Confidence | Multiplier |
|---|---|
| Confirmed | 1.0 |
| High | 1.0 |
| Medium | 0.7 |
| Low | 0.4 |

Worked example — the insecure fixture produces (among others):

| Finding | Severity | Confidence | Points |
|---|---|---|---|
| The APK is debuggable | Critical | Confirmed | 25 |
| JavaScript bridge exposed | Critical | High | 25 |
| App data can be backed up | High | Confirmed | 12 |
| Cleartext traffic allowed | High | Confirmed | 12 |
| Exported service | High | Confirmed | 12 |

Because deductions routinely exceed 100 for deliberately insecure apps, the score clamps at 0. The
`ScoreCalculator.totalSecurityDeductions()` value is what the breakdown table sums to; the score is
that total subtracted from 100 and clamped.

## Performance score

| Deduction | Points | Basis |
|---|---|---|
| Duplicate resources | 2 per MB, max 20 | exact wasted bytes from SHA-256 matches |
| Oversized images | 1.5 per MB, max 25 | measured image bytes |
| Multiple ABIs in one APK | 10 | native libraries for more than one ABI |
| Multidex build | 5 | more than one `classes*.dex` |
| APK size | 4 (≥25 MB), 8 (≥60 MB), 15 (≥150 MB) | file size |

## Why a score is not a verdict

A score compresses many judgements into one number, and it can only reflect the rules that ran. The
report therefore prints, next to the score: the full deduction list, the rule-set version, the list
of checks performed, and this disclaimer:

> The security score is a triage aid computed from the findings listed in this report. It is not a
> certification, and a high score does not mean the app is secure: static analysis cannot see
> runtime behaviour, server-side controls or code hidden behind packers and heavy reflection.

The score is never shown without the severity counts and the findings themselves.
