import type { ReactNode } from "react";
import { clsx } from "clsx";

export function Panel({
  children,
  className,
  title,
  meta
}: {
  children: ReactNode;
  className?: string;
  title?: string;
  meta?: ReactNode;
}) {
  return (
    <section className={clsx("ui-panel", className)}>
      {(title || meta) && (
        <header className="ui-panel-header">
          {title ? <h2>{title}</h2> : <span />}
          {meta}
        </header>
      )}
      {children}
    </section>
  );
}
