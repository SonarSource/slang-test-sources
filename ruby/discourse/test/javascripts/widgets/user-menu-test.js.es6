import { moduleForWidget, widgetTest } from "helpers/widget-test";

moduleForWidget("user-menu");

widgetTest("basics", {
  template: '{{mount-widget widget="user-menu"}}',

  test(assert) {
    assert.ok(this.$(".user-menu").length);
    assert.ok(this.$(".user-activity-link").length);
    assert.ok(this.$(".user-bookmarks-link").length);
    assert.ok(this.$(".user-preferences-link").length);
    assert.ok(this.$(".notifications").length);
    assert.ok(this.$(".dismiss-link").length);
  }
});

widgetTest("log out", {
  template: '{{mount-widget widget="user-menu" logout="logout"}}',

  beforeEach() {
    this.on("logout", () => (this.loggedOut = true));
  },

  async test(assert) {
    assert.ok(this.$(".logout").length);

    await click(".logout");
    assert.ok(this.loggedOut);
  }
});

widgetTest("private messages - disabled", {
  template: '{{mount-widget widget="user-menu"}}',
  beforeEach() {
    this.siteSettings.enable_personal_messages = false;
  },

  test(assert) {
    assert.ok(!this.$(".user-pms-link").length);
  }
});

widgetTest("private messages - enabled", {
  template: '{{mount-widget widget="user-menu"}}',
  beforeEach() {
    this.siteSettings.enable_personal_messages = true;
  },

  test(assert) {
    assert.ok(this.$(".user-pms-link").length);
  }
});

widgetTest("anonymous", {
  template:
    '{{mount-widget widget="user-menu" toggleAnonymous="toggleAnonymous"}}',

  beforeEach() {
    this.currentUser.setProperties({ is_anonymous: false, trust_level: 3 });
    this.siteSettings.allow_anonymous_posting = true;
    this.siteSettings.anonymous_posting_min_trust_level = 3;

    this.on("toggleAnonymous", () => (this.anonymous = true));
  },

  async test(assert) {
    assert.ok(this.$(".enable-anonymous").length);
    await click(".enable-anonymous");
    assert.ok(this.anonymous);
  }
});

widgetTest("anonymous - disabled", {
  template: '{{mount-widget widget="user-menu"}}',

  beforeEach() {
    this.siteSettings.allow_anonymous_posting = false;
  },

  test(assert) {
    assert.ok(!this.$(".enable-anonymous").length);
  }
});

widgetTest("anonymous - switch back", {
  template:
    '{{mount-widget widget="user-menu" toggleAnonymous="toggleAnonymous"}}',

  beforeEach() {
    this.currentUser.setProperties({ is_anonymous: true });
    this.siteSettings.allow_anonymous_posting = true;

    this.on("toggleAnonymous", () => (this.anonymous = true));
  },

  async test(assert) {
    assert.ok(this.$(".disable-anonymous").length);
    await click(".disable-anonymous");
    assert.ok(this.anonymous);
  }
});
