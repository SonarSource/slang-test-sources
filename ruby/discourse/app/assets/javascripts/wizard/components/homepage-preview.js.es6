import { observes } from "ember-addons/ember-computed-decorators";
import {
  createPreviewComponent,
  LOREM,
  darkLightDiff
} from "wizard/lib/preview";

export default createPreviewComponent(659, 320, {
  logo: null,
  avatar: null,

  @observes("step.fieldsById.homepage_style.value")
  styleChanged() {
    this.triggerRepaint();
  },

  images() {
    return {
      logo: this.get("wizard").getLogoUrl(),
      avatar: "/images/wizard/trout.png"
    };
  },

  paint(ctx, colors, width, height) {
    this.drawFullHeader(colors);

    if (this.get("step.fieldsById.homepage_style.value") === "latest") {
      this.drawPills(colors, height * 0.15);
      this.renderLatest(ctx, colors, width, height);
    } else if (
      ["categories_only", "categories_with_featured_topics"].includes(
        this.get("step.fieldsById.homepage_style.value")
      )
    ) {
      this.drawPills(colors, height * 0.15, { categories: true });
      this.renderCategories(ctx, colors, width, height);
    } else {
      this.drawPills(colors, height * 0.15, { categories: true });
      this.renderCategoriesWithTopics(ctx, colors, width, height);
    }
  },

  renderCategories(ctx, colors, width, height) {
    const textColor = darkLightDiff(colors.primary, colors.secondary, 50, 50);
    const margin = height * 0.03;
    const bodyFontSize = height / 440.0;
    const titles = this.getTitles();
    let categoryHeight = height / 6;

    const drawLine = (x, y) => {
      ctx.beginPath();
      ctx.strokeStyle = darkLightDiff(
        colors.primary,
        colors.secondary,
        90,
        -75
      );
      ctx.moveTo(margin + x, y);
      ctx.lineTo(width - margin, y);
      ctx.stroke();
    };

    const cols = [0.025, 0.45, 0.53, 0.58, 0.94, 0.96].map(c => c * width);

    const headingY = height * 0.33;
    ctx.font = `${bodyFontSize * 0.9}em 'Arial'`;
    ctx.fillStyle = textColor;
    ctx.fillText("Category", cols[0], headingY);
    if (
      this.get("step.fieldsById.homepage_style.value") === "categories_only"
    ) {
      ctx.fillText("Topics", cols[4], headingY);
    } else {
      ctx.fillText("Topics", cols[1], headingY);
      ctx.fillText("Latest", cols[2], headingY);
      categoryHeight = height / 5;
    }

    let y = headingY + bodyFontSize * 12;
    ctx.lineWidth = 2;
    drawLine(0, y);
    drawLine(width / 2, y);

    // Categories
    this.categories().forEach(category => {
      const textPos = y + categoryHeight * 0.35;
      ctx.font = `Bold ${bodyFontSize * 1.1}em 'Arial'`;
      ctx.fillStyle = colors.primary;
      ctx.fillText(category.name, cols[0], textPos);

      ctx.font = `${bodyFontSize * 0.8}em 'Arial'`;
      ctx.fillStyle = textColor;
      ctx.fillText(
        titles[0],
        cols[0] - margin * 0.25,
        textPos + categoryHeight * 0.36
      );

      ctx.beginPath();
      ctx.moveTo(margin, y);
      ctx.strokeStyle = category.color;
      ctx.lineWidth = 3.5;
      ctx.lineTo(margin, y + categoryHeight);
      ctx.stroke();

      if (
        this.get("step.fieldsById.homepage_style.value") ===
        "categories_with_featured_topics"
      ) {
        ctx.font = `${bodyFontSize}em 'Arial'`;
        ctx.fillText(
          Math.floor(Math.random() * 90) + 10,
          cols[1] + 15,
          textPos
        );
      } else {
        ctx.font = `${bodyFontSize}em 'Arial'`;
        ctx.fillText(Math.floor(Math.random() * 90) + 10, cols[5], textPos);
      }

      y += categoryHeight;
      ctx.lineWidth = 1;
      drawLine(0, y);
    });

    // Featured Topics
    if (
      this.get("step.fieldsById.homepage_style.value") ===
      "categories_with_featured_topics"
    ) {
      const topicHeight = height / 15;

      y = headingY + bodyFontSize * 22;
      ctx.lineWidth = 1;
      ctx.fillStyle = colors.tertiary;

      titles.forEach(title => {
        ctx.font = `${bodyFontSize}em 'Arial'`;
        const textPos = y + topicHeight * 0.35;
        ctx.fillStyle = colors.tertiary;
        ctx.fillText(`${title}`, cols[2], textPos);
        y += topicHeight;
      });
    }
  },

  renderCategoriesWithTopics(ctx, colors, width, height) {
    const textColor = darkLightDiff(colors.primary, colors.secondary, 50, 50);
    const margin = height * 0.03;
    const bodyFontSize = height / 440.0;

    const drawLine = (x, y) => {
      ctx.beginPath();
      ctx.strokeStyle = darkLightDiff(
        colors.primary,
        colors.secondary,
        90,
        -75
      );
      ctx.moveTo(margin + x, y);
      ctx.lineTo(margin + x + (width * 0.9) / 2, y);
      ctx.stroke();
    };

    const cols = [0.025, 0.42, 0.53, 0.58, 0.94].map(c => c * width);

    const headingY = height * 0.33;
    ctx.font = `${bodyFontSize * 0.9}em 'Arial'`;
    ctx.fillStyle = textColor;
    ctx.fillText("Category", cols[0], headingY);
    ctx.fillText("Topics", cols[1], headingY);
    if (
      this.get("step.fieldsById.homepage_style.value") ===
      "categories_and_latest_topics"
    ) {
      ctx.fillText("Latest", cols[2], headingY);
    } else {
      ctx.fillText("Top", cols[2], headingY);
    }

    let y = headingY + bodyFontSize * 12;
    ctx.lineWidth = 2;
    drawLine(0, y);
    drawLine(width / 2, y);

    const categoryHeight = height / 6;
    const titles = this.getTitles();

    // Categories
    this.categories().forEach(category => {
      const textPos = y + categoryHeight * 0.35;
      ctx.font = `Bold ${bodyFontSize * 1.1}em 'Arial'`;
      ctx.fillStyle = colors.primary;
      ctx.fillText(category.name, cols[0], textPos);

      ctx.font = `${bodyFontSize * 0.8}em 'Arial'`;
      ctx.fillStyle = textColor;
      ctx.fillText(
        titles[0],
        cols[0] - margin * 0.25,
        textPos + categoryHeight * 0.36
      );

      ctx.beginPath();
      ctx.moveTo(margin, y);
      ctx.strokeStyle = category.color;
      ctx.lineWidth = 3.5;
      ctx.lineTo(margin, y + categoryHeight);
      ctx.stroke();

      ctx.font = `${bodyFontSize}em 'Arial'`;
      ctx.fillText(Math.floor(Math.random() * 90) + 10, cols[1] + 15, textPos);

      y += categoryHeight;
      ctx.lineWidth = 1;
      drawLine(0, y);
    });

    // Latest/Top Topics
    const topicHeight = height / 8;
    const avatarSize = topicHeight * 0.7;
    y = headingY + bodyFontSize * 12;
    ctx.lineWidth = 1;
    ctx.fillStyle = textColor;

    titles.forEach(title => {
      const category = this.categories()[0];
      ctx.font = `${bodyFontSize}em 'Arial'`;
      const textPos = y + topicHeight * 0.45;
      ctx.fillStyle = textColor;
      this.scaleImage(
        this.avatar,
        cols[2],
        y + margin * 0.6,
        avatarSize,
        avatarSize
      );
      ctx.fillText(title, cols[3], textPos);

      ctx.font = `Bold ${bodyFontSize}em 'Arial'`;
      ctx.fillText(Math.floor(Math.random() * 90) + 10, cols[4], textPos);
      ctx.font = `${bodyFontSize}em 'Arial'`;
      ctx.fillText(`1h`, cols[4], textPos + topicHeight * 0.4);

      ctx.beginPath();
      ctx.fillStyle = category.color;
      const badgeSize = topicHeight * 0.1;
      ctx.font = `Bold ${bodyFontSize * 0.5}em 'Arial'`;
      ctx.rect(
        cols[3] + margin * 0.5,
        y + topicHeight * 0.65,
        badgeSize,
        badgeSize
      );
      ctx.fill();

      ctx.fillStyle = colors.primary;
      ctx.fillText(
        category.name,
        cols[3] + badgeSize * 3,
        y + topicHeight * 0.76
      );
      y += topicHeight;

      drawLine(width / 2, y);
    });
  },

  getTitles() {
    return LOREM.split(".")
      .slice(0, 8)
      .map(t => t.substring(0, 40));
  },

  renderLatest(ctx, colors, width, height) {
    const rowHeight = height / 10.0;
    const textColor = darkLightDiff(colors.primary, colors.secondary, 50, 50);
    const bodyFontSize = height / 440.0;

    ctx.font = `${bodyFontSize}em 'Arial'`;

    const margin = height * 0.03;

    const drawLine = y => {
      ctx.beginPath();
      ctx.strokeStyle = darkLightDiff(
        colors.primary,
        colors.secondary,
        90,
        -75
      );
      ctx.moveTo(margin, y);
      ctx.lineTo(width - margin, y);
      ctx.stroke();
    };

    const cols = [0.02, 0.5, 0.65, 0.8, 0.87, 0.93].map(c => c * width);

    // Headings
    const headingY = height * 0.33;

    ctx.fillStyle = textColor;
    ctx.font = `${bodyFontSize * 0.9}em 'Arial'`;
    ctx.fillText("Topic", cols[0], headingY);
    ctx.fillText("Category", cols[1], headingY);
    ctx.fillText("Users", cols[2], headingY);
    ctx.fillText("Replies", cols[3], headingY);
    ctx.fillText("Views", cols[4], headingY);
    ctx.fillText("Activity", cols[5], headingY);

    // Topics
    let y = headingY + rowHeight / 2.6;
    ctx.lineWidth = 2;
    drawLine(y);

    ctx.font = `${bodyFontSize}em 'Arial'`;
    ctx.lineWidth = 1;
    this.getTitles().forEach(title => {
      const textPos = y + rowHeight * 0.7;
      ctx.fillStyle = textColor;
      ctx.fillText(title, cols[0], textPos);

      const category = this.categories()[0];
      ctx.beginPath();
      ctx.fillStyle = category.color;
      const badgeSize = rowHeight * 0.2;
      ctx.font = `Bold ${bodyFontSize * 0.5}em 'Arial'`;
      ctx.rect(cols[1], y + rowHeight * 0.5, badgeSize, badgeSize);
      ctx.fill();

      ctx.fillStyle = colors.primary;
      ctx.fillText(
        category.name,
        cols[1] + badgeSize * 1.5,
        y + rowHeight * 0.65
      );
      this.scaleImage(
        this.avatar,
        cols[2],
        y + rowHeight * 0.3,
        rowHeight * 0.5,
        rowHeight * 0.5
      );

      ctx.fillStyle = textColor;
      ctx.font = `${bodyFontSize}em 'Arial'`;
      for (let j = 3; j <= 5; j++) {
        ctx.fillText(
          j === 5 ? "1h" : Math.floor(Math.random() * 90) + 10,
          cols[j] + margin,
          y + rowHeight * 0.7
        );
      }
      drawLine(y + rowHeight * 1);
      y += rowHeight;
    });
  }
});
