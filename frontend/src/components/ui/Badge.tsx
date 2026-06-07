import type { ReactNode } from "react";
import { clsx } from "clsx";

type BadgeTone = "neutral" | "cyan" | "green" | "amber" | "red";

export function Badge({
  children,
  tone = "neutral"
}: {
  children: ReactNode;
  tone?: BadgeTone;
}) {
  return <span className={clsx("ui-badge", `ui-badge-${tone}`)}>{children}</span>;
}
