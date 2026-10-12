# Employee Directory — Changes: Shared Address/Personal-Info/Emergency-Contact Value Objects

## Status: Implemented (2026-09-17). See
`C:\Users\Yamraj\.claude\plans\read-this-and-take-purring-candy.md` for the full
discussion, duplication inventory, and reference-project comparison this distills.

**Deviation from the original sketch below**: the mapping helpers (`AddressPojo` <->
`AddressForm`/`AddressData`) turned out to have a second consumer beyond `DirectoryFlow`
-- `EmpFlow.toData` builds `SessionData`'s structured address the same way. Rather than a
private `DirectoryFlow`-only helper, the mapping lives as `AddressForm.toPojo()` and
`AddressData.from(AddressPojo)` on the value objects themselves, so both Flows call the
same one-liner. Also found: `SessionData` already had a field named `address` (the
free-text one) -- the new structured-address field there is named `structuredAddress` to
avoid the collision; `@JsonUnwrapped` uses `AddressData`'s own field names on the wire
regardless, so this is a Java-only name, no wire-contract change.

## Overview

Pure internal refactor of `model/form/`, `model/data/`, and `workflow/DirectoryFlow.java`
— no wire-contract change, no schema change, no new endpoint. Replaces three verbatim-
duplicated field blocks (address, personal-info, emergency-contact) with shared
`*Form`/`*Data` value types, composed via Jackson's `@JsonUnwrapped` so the JSON body
stays exactly as `API-REQUIREMENTS.md` already specifies it. Motivated by the org's own
`coding.md` rule ("group fields that always travel together into a value object rather
than repeating them") and KISS/DRY, per explicit user direction.

Today, `address1/address2/city/landmark/postalCode/state/country` is duplicated in
`DirectoryForm`, `DirectoryProfileForm`, `DirectoryData`, and `SessionData` (4 files);
the personal-info block (`dateOfBirth/gender/nationality/maritalStatus/bloodGroup/
personalEmail`) and emergency-contact block (`emergencyName/emergencyRelationship/
emergencyPhone`) are each duplicated in `DirectoryForm`/`DirectoryProfileForm`/
`DirectoryData` (3 files). `DirectoryFlow` additionally hand-copies the address fields
field-by-field, twice (create path and update path), then unpacks them again into the
response — the same 7-line copy written three separate times in one file.

## Data Models

New value-object twins (plain DTOs, no JPA annotations — kept separate from the
persistence-layer `pojo/AddressPojo` so the wire layer stays decoupled from the entity
layer, per `coding.md`'s "`*Data` that's field-for-field its `*Pojo` usually means the
mapping step was skipped"):

- `model/form/AddressForm.java` — `address1, address2, city, landmark, postalCode,
  state, country` (all `String`, matching `AddressPojo`'s field names/lengths).
- `model/data/AddressData.java` — same 7 fields.
- `model/form/PersonalInfoForm.java` — `dateOfBirth: LocalDate, gender, nationality,
  maritalStatus, bloodGroup, personalEmail: String`.
- `model/data/PersonalInfoData.java` — same 6 fields.
- `model/form/EmergencyContactForm.java` — `emergencyName, emergencyRelationship,
  emergencyPhone: String`.
- `model/data/EmergencyContactData.java` — same 3 fields.

`DirectoryForm`/`DirectoryProfileForm` each replace their loose address/personal/
emergency fields with three `@JsonUnwrapped`-annotated nested fields
(`private AddressForm address = new AddressForm();`, etc. — `DirectoryProfileForm` omits
none of the three, since it carries all of them today). `DirectoryData` does the same
with `AddressData`/`PersonalInfoData`/`EmergencyContactData`. `SessionData` only gains
`@JsonUnwrapped AddressData address` (it doesn't carry personal-info/emergency fields
today).

`@JsonUnwrapped` flattens the nested object's fields onto the parent's JSON level in both
directions, so `{"address1": "...", "city": "...", ...}` is unchanged on the wire; only
the Java accessor path changes (`form.getAddress1()` → `form.getAddress().getAddress1()`).

## Business Logic

`workflow/DirectoryFlow.java`: the two identical 8-line blocks that build an `AddressPojo`
from loose form fields (create path, update path) collapse into one private helper,
`toAddressPojo(AddressForm)`, called from both. The reverse mapping into
`DirectoryData`/`SessionData` (`data.setAddress1(a.getAddress1()); ...`) becomes a single
`toAddressData(AddressPojo)` helper, replacing two more repeated 7-line blocks. No
change to validation logic (`validateAddress`, `validateEmergencyContact`) beyond reading
from the new nested types instead of loose form getters — the rules themselves (address1/
city/country required together, postal/state conditional-by-country, emergency contact
all-or-nothing) are unchanged.

## Relevant Files

New:
- `model/form/AddressForm.java`, `model/data/AddressData.java`
- `model/form/PersonalInfoForm.java`, `model/data/PersonalInfoData.java`
- `model/form/EmergencyContactForm.java`, `model/data/EmergencyContactData.java`

Modified:
- `model/form/DirectoryForm.java`, `model/form/DirectoryProfileForm.java`
- `model/data/DirectoryData.java`, `model/data/SessionData.java`
- `workflow/DirectoryFlow.java`
- `prompts/features/employee-directory.md` — append a short "Update" note once shipped,
  per that doc's existing running-changelog style (this `.changes.md` folds back into it
  and gets deleted, per `feature.md`'s convention).

Not touched: `EmpForm`, `EmpImportRowForm`, `EmpRowData`, `SessionData`'s free-text
`address` field, `LeaveBalanceData`/`LeaveRequestData`'s leaveType triplet, the auth-form
trio (`orgKey`/`email`/`captchaToken`) — all deliberately left as-is; see the plan doc's
Part 7.5 for why each is out of scope.

## Verification

1. `mvn -o compile test-compile`.
2. `mvn -o test -Dtest=Directory*` — existing tests assert the flat wire JSON; with
   `@JsonUnwrapped` wired correctly, fixtures shouldn't need JSON-shape changes. Any test
   that constructs a `DirectoryForm`/`DirectoryData` directly in Java (not via JSON) needs
   its field access updated to go through the nested object.
3. Manual smoke check: `POST`/`PATCH /api/directory` and
   `POST /api/employees/me/directory-profile` with the existing flat JSON body; confirm
   `GET /api/directory` / `GET /api/auth/me` still return flat `address1`/`city`/...
   keys, unchanged.
