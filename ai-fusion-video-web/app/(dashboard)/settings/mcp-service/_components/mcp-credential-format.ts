const expiryFormat = new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" });

export function formatCredentialExpiry(expiresAt: string | null) {
  return expiresAt === null ? "永久有效" : `有效至 ${expiryFormat.format(new Date(expiresAt))}`;
}
