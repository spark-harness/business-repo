export type HongKongPhoneNumber = {
  countryCode: "+852";
  localNumber: string;
  masked: string;
};

export function parseHongKongPhoneNumber(
  countryCode: string,
  rawLocalNumber: string,
): HongKongPhoneNumber {
  if (countryCode !== "+852") {
    throw new Error("unsupported_country");
  }

  const localNumber = rawLocalNumber.replace(/\D/g, "");
  if (!/^[456789]\d{7}$/.test(localNumber)) {
    throw new Error("invalid_phone_number");
  }

  return {
    countryCode: "+852",
    localNumber,
    masked: `+852 **** ${localNumber.slice(-4)}`,
  };
}
