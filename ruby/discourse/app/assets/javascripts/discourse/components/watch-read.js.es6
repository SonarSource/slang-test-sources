import isElementInViewport from "discourse/lib/is-element-in-viewport";

export default Ember.Component.extend({
  didInsertElement() {
    this._super();
    const currentUser = this.currentUser;
    if (!currentUser) {
      return;
    }

    const path = this.get("path");
    if (path === "faq" || path === "guidelines") {
      $(window).on("load.faq resize.faq scroll.faq", () => {
        const faqUnread = !currentUser.get("read_faq");
        if (faqUnread && isElementInViewport($(".contents p").last())) {
          this.sendAction();
        }
      });
    }
  },

  willDestroyElement() {
    this._super();
    $(window).off("load.faq resize.faq scroll.faq");
  }
});
