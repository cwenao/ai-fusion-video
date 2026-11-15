import { SquareArrowOutUpRight } from "lucide-react";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import type { StoryboardItem } from "@/lib/api/storyboard";
import { storyboardVideoHref } from "@/lib/storyboard-video-source";

export function StoryboardVideoToolLink({ projectId, item, compact, className }: {
  projectId: number;
  item: StoryboardItem;
  compact?: boolean;
  className?: string;
}) {
  return (
    <Button
      variant={compact ? "ghost" : "outline"}
      size={compact ? "icon-xs" : "default"}
      nativeButton={false}
      role="link"
      className={className}
      render={<Link href={storyboardVideoHref({ projectId, shotId: item.id, shotVersion: item.version })}
        aria-label="打开视频工具" title="将镜头提示词和参考素材带入视频工具"
        onClick={(event) => {
          event.stopPropagation();
        }} />}
    >
      <SquareArrowOutUpRight />
      {!compact && "打开视频工具"}
    </Button>
  );
}
