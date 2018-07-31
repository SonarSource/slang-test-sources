import { on, observes } from "ember-addons/ember-computed-decorators";

// Mix this in to a view that has a `archetype` property to automatically
// add it to the body as the view is entered / left / model is changed.
// This is used for keeping the `body` style in sync for the background image.
export default {
  _cleanUp() {
    $("body").removeClass((_, css) =>
      (css.match(/\barchetype-\S+/g) || []).join(" ")
    );
  },

  @observes("archetype")
  @on("init")
  _archetypeChanged() {
    const archetype = this.get("archetype");
    this._cleanUp();

    if (archetype) {
      $("body").addClass("archetype-" + archetype);
    }
  },

  willDestroyElement() {
    this._super();
    this._cleanUp();
  }
};
