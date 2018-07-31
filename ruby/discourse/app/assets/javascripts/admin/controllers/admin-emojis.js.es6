import { ajax } from "discourse/lib/ajax";
export default Ember.Controller.extend({
  sortedEmojis: Ember.computed.sort("model", "emojiSorting"),
  emojiSorting: ["name"],

  actions: {
    emojiUploaded(emoji) {
      emoji.url += "?t=" + new Date().getTime();
      this.get("model").pushObject(Ember.Object.create(emoji));
    },

    destroy(emoji) {
      return bootbox.confirm(
        I18n.t("admin.emoji.delete_confirm", { name: emoji.get("name") }),
        I18n.t("no_value"),
        I18n.t("yes_value"),
        destroy => {
          if (destroy) {
            return ajax("/admin/customize/emojis/" + emoji.get("name"), {
              type: "DELETE"
            }).then(() => {
              this.get("model").removeObject(emoji);
            });
          }
        }
      );
    }
  }
});
