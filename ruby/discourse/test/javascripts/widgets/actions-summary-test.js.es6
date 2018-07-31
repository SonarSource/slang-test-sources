import { moduleForWidget, widgetTest } from "helpers/widget-test";

moduleForWidget("actions-summary");

widgetTest("listing actions", {
  template: '{{mount-widget widget="actions-summary" args=args}}',
  beforeEach() {
    this.set("args", {
      actionsSummary: [
        { id: 1, action: "off_topic", description: "very off topic" },
        { id: 2, action: "spam", description: "suspicious message" }
      ]
    });
  },
  async test(assert) {
    assert.equal(this.$(".post-actions .post-action").length, 2);

    await click(".post-action:eq(0) .action-link a");
    assert.equal(
      this.$(".post-action:eq(0) img.avatar").length,
      1,
      "clicking it shows the user"
    );
  }
});

widgetTest("undo", {
  template:
    '{{mount-widget widget="actions-summary" args=args undoPostAction=undoPostAction}}',
  beforeEach() {
    this.set("args", {
      actionsSummary: [
        { action: "off_topic", description: "very off topic", canUndo: true }
      ]
    });

    this.set("undoPostAction", () => (this.undid = true));
  },
  async test(assert) {
    assert.equal(this.$(".post-actions .post-action").length, 1);

    await click(".action-link.undo");
    assert.ok(this.undid, "it triggered the action");
  }
});

widgetTest("deferFlags", {
  template:
    '{{mount-widget widget="actions-summary" args=args deferPostActionFlags="deferPostActionFlags"}}',
  beforeEach() {
    this.set("args", {
      actionsSummary: [
        {
          action: "off_topic",
          description: "very off topic",
          canDeferFlags: true,
          count: 1
        }
      ]
    });

    this.on("deferPostActionFlags", () => (this.deferred = true));
  },
  async test(assert) {
    assert.equal(this.$(".post-actions .post-action").length, 1);

    await click(".action-link.defer-flags");
    assert.ok(this.deferred, "it triggered the action");
  }
});

widgetTest("post deleted", {
  template: '{{mount-widget widget="actions-summary" args=args}}',
  beforeEach() {
    this.set("args", {
      deleted_at: "2016-01-01",
      deletedByUsername: "eviltrout",
      deletedByAvatarTemplate: "/images/avatar.png"
    });
  },
  test(assert) {
    assert.ok(
      this.$(".post-action .d-icon-trash-o").length === 1,
      "it has the deleted icon"
    );
    assert.ok(
      this.$(".avatar[title=eviltrout]").length === 1,
      "it has the deleted by avatar"
    );
  }
});
