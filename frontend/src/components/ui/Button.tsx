import type { ButtonHTMLAttributes, ReactNode } from "react";
import { Loader2 } from "lucide-react";
import { clsx } from "clsx";

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  icon?: ReactNode;
  loading?: boolean;
  variant?: "primary" | "secondary" | "danger" | "ghost";
};

export function Button({
  children,
  className,
  icon,
  loading = false,
  variant = "secondary",
  disabled,
  ...props
}: ButtonProps) {
  return (
    <button
      className={clsx("ui-button", `ui-button-${variant}`, className)}
      disabled={disabled || loading}
      {...props}
    >
      {loading ? <Loader2 className="ui-button-icon is-spinning" size={16} /> : icon}
      <span>{children}</span>
    </button>
  );
}
