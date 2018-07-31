export default Ember.Component.extend({
  action: "showCreateAccount",

  actions: {
    neverShow() {
      this.keyValueStore.setItem("anon-cta-never", "t");
      this.session.set("showSignupCta", false);
    },
    hideForSession() {
      this.session.set("hideSignupCta", true);
      this.keyValueStore.setItem("anon-cta-hidden", new Date().getTime());
      Em.run.later(() => this.session.set("showSignupCta", false), 20 * 1000);
    },
    showCreateAccount() {
      this.sendAction();
    }
  },

  _turnOffIfHidden: function() {
    if (this.session.get("hideSignupCta")) {
      this.session.set("showSignupCta", false);
    }
  }.on("willDestroyElement")
});
