export type Nationality =
  | "chinese"
  | "hong_kong"
  | "british"
  | "indian"
  | "filipino"
  | "indonesian"
  | "pakistani"
  | "american"
  | "australian"
  | "canadian"
  | "other";

export type IdentityProfileInput = {
  hkidBody: string;
  hkidCheckDigit: string;
  firstName: string;
  lastName: string;
  chineseName: string;
  nationality: Nationality | "";
  dateOfBirth: string;
};

export type IdentityProfile = IdentityProfileInput & {
  nationality: Nationality;
};

export type IdentityProfileField = keyof IdentityProfileInput | "form";

export type IdentityProfileValidationError = {
  field: IdentityProfileField;
  message: string;
};

const nationalities: readonly Nationality[] = [
  "chinese",
  "hong_kong",
  "british",
  "indian",
  "filipino",
  "indonesian",
  "pakistani",
  "american",
  "australian",
  "canadian",
  "other",
];

export function nationalityOptions(): readonly { value: Nationality; label: string }[] {
  return [
    { value: "chinese", label: "Chinese" },
    { value: "hong_kong", label: "Hong Kong" },
    { value: "british", label: "British" },
    { value: "indian", label: "Indian" },
    { value: "filipino", label: "Filipino" },
    { value: "indonesian", label: "Indonesian" },
    { value: "pakistani", label: "Pakistani" },
    { value: "american", label: "American" },
    { value: "australian", label: "Australian" },
    { value: "canadian", label: "Canadian" },
    { value: "other", label: "Other" },
  ];
}

export function normalizeIdentityProfile(input: IdentityProfileInput): IdentityProfile {
  const error = validateIdentityProfile(input);
  if (error) {
    throw error;
  }
  return {
    hkidBody: input.hkidBody.trim().toUpperCase(),
    hkidCheckDigit: input.hkidCheckDigit.trim().toUpperCase(),
    firstName: input.firstName.trim(),
    lastName: input.lastName.trim(),
    chineseName: input.chineseName.trim(),
    nationality: input.nationality as Nationality,
    dateOfBirth: input.dateOfBirth,
  };
}

export function validateIdentityProfile(input: IdentityProfileInput): IdentityProfileValidationError | null {
  const hkidBody = input.hkidBody.trim().toUpperCase();
  const hkidCheckDigit = input.hkidCheckDigit.trim().toUpperCase();
  if (!/^[A-Z][0-9]{6}$/.test(hkidBody) || !/^[0-9A]$/.test(hkidCheckDigit) || expectedCheckDigit(hkidBody) !== hkidCheckDigit) {
    return { field: "hkidBody", message: "Enter a valid HKID." };
  }
  if (!/^[A-Za-z]+$/.test(input.firstName.trim())) {
    return { field: "firstName", message: "Use English letters only." };
  }
  if (!/^[A-Za-z]+$/.test(input.lastName.trim())) {
    return { field: "lastName", message: "Use English letters only." };
  }
  if (!input.chineseName.trim()) {
    return { field: "chineseName", message: "Enter your Chinese name." };
  }
  if (!nationalities.includes(input.nationality as Nationality)) {
    return { field: "nationality", message: "Select a nationality." };
  }
  if (!validAge(input.dateOfBirth)) {
    return { field: "dateOfBirth", message: "Age must be between 18 and 60." };
  }
  return null;
}

function expectedCheckDigit(body: string): string {
  const letterValue = body.charCodeAt(0) - "A".charCodeAt(0) + 10;
  let sum = 36 * 9 + letterValue * 8;
  for (let index = 1; index < body.length; index += 1) {
    sum += Number(body[index]) * (8 - index);
  }
  const check = 11 - (sum % 11);
  if (check === 11) {
    return "0";
  }
  if (check === 10) {
    return "A";
  }
  return String(check);
}

function validAge(value: string): boolean {
  if (!/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(value)) {
    return false;
  }
  const birthDate = parseLocalDate(value);
  if (!birthDate) {
    return false;
  }
  const today = hongKongToday();
  let age = today.year - birthDate.year;
  if (
    today.month < birthDate.month ||
    (today.month === birthDate.month && today.day < birthDate.day)
  ) {
    age -= 1;
  }
  return age >= 18 && age <= 60;
}

function parseLocalDate(value: string): { year: number; month: number; day: number } | null {
  const [year, month, day] = value.split("-").map(Number);
  if (!year || !month || !day) {
    return null;
  }
  const date = new Date(Date.UTC(year, month - 1, day));
  if (
    date.getUTCFullYear() !== year ||
    date.getUTCMonth() !== month - 1 ||
    date.getUTCDate() !== day
  ) {
    return null;
  }
  return { year, month, day };
}

function hongKongToday(): { year: number; month: number; day: number } {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Hong_Kong",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(new Date());
  const value = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return {
    year: Number(value.year),
    month: Number(value.month),
    day: Number(value.day),
  };
}
