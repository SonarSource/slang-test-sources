import {
  default as computed,
  observes
} from "ember-addons/ember-computed-decorators";
import User from "discourse/models/user";
import InputValidation from "discourse/models/input-validation";
import debounce from "discourse/lib/debounce";

export default Ember.Component.extend({
  disableSave: null,
  nameInput: null,

  didInsertElement() {
    this._super();
    const name = this.get("model.name");

    if (name) {
      this.set("nameInput", name);
    } else {
      this.set("disableSave", true);
    }
  },

  @computed("basicNameValidation", "uniqueNameValidation")
  nameValidation(basicNameValidation, uniqueNameValidation) {
    return uniqueNameValidation ? uniqueNameValidation : basicNameValidation;
  },

  @observes("nameInput")
  _validateName() {
    name = this.get("nameInput");
    if (name === this.get("model.name")) return;

    if (name === undefined) {
      return this._failedInputValidation();
    }

    if (name === "") {
      this.set("uniqueNameValidation", null);
      return this._failedInputValidation(I18n.t("admin.groups.new.name.blank"));
    }

    if (name.length < this.siteSettings.min_username_length) {
      return this._failedInputValidation(
        I18n.t("admin.groups.new.name.too_short")
      );
    }

    if (name.length > this.siteSettings.max_username_length) {
      return this._failedInputValidation(
        I18n.t("admin.groups.new.name.too_long")
      );
    }

    this.checkGroupName();

    return this._failedInputValidation(
      I18n.t("admin.groups.new.name.checking")
    );
  },

  checkGroupName: debounce(function() {
    name = this.get("nameInput");
    if (Ember.isEmpty(name)) return;

    User.checkUsername(name).then(response => {
      const validationName = "uniqueNameValidation";

      if (response.available) {
        this.set(
          validationName,
          InputValidation.create({
            ok: true,
            reason: I18n.t("admin.groups.new.name.available")
          })
        );

        this.set("disableSave", false);
        this.set("model.name", this.get("nameInput"));
      } else {
        let reason;

        if (response.errors) {
          reason = response.errors.join(" ");
        } else {
          reason = I18n.t("admin.groups.new.name.not_available");
        }

        this.set(validationName, this._failedInputValidation(reason));
      }
    });
  }, 500),

  _failedInputValidation(reason) {
    this.set("disableSave", true);

    const options = { failed: true };
    if (reason) options.reason = reason;
    this.set("basicNameValidation", InputValidation.create(options));
  }
});
