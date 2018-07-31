import computed from "ember-addons/ember-computed-decorators";
import { userPath } from "discourse/lib/url";

function normalize(name) {
  return name.replace(/[\-\_ \.]/g, "").toLowerCase();
}

export default Ember.Component.extend({
  classNameBindings: [":user-info", "size"],
  attributeBindings: ["data-username"],
  size: "small",

  @computed("user.username")
  userPath(username) {
    return userPath(username);
  },

  "data-username": Em.computed.alias("user.username"),

  // TODO: In later ember releases `hasBlock` works without this
  hasBlock: Em.computed.alias("template"),

  @computed("user.name", "user.username")
  name(name, username) {
    if (name && normalize(username) !== normalize(name)) {
      return name;
    }
  }
});
