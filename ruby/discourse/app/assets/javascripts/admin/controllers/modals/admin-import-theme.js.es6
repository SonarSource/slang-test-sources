import ModalFunctionality from "discourse/mixins/modal-functionality";
import { ajax } from "discourse/lib/ajax";
import { popupAjaxError } from "discourse/lib/ajax-error";
import { observes } from "ember-addons/ember-computed-decorators";

export default Ember.Controller.extend(ModalFunctionality, {
  local: Ember.computed.equal("selection", "local"),
  remote: Ember.computed.equal("selection", "remote"),
  selection: "local",
  adminCustomizeThemes: Ember.inject.controller(),
  loading: false,
  keyGenUrl: "/admin/themes/generate_key_pair",
  importUrl: "/admin/themes/import",

  checkPrivate: Ember.computed.match("uploadUrl", /^git/),

  @observes("privateChecked")
  privateWasChecked() {
    const checked = this.get("privateChecked");
    if (checked && !this._keyLoading) {
      this._keyLoading = true;
      ajax(this.get("keyGenUrl"), { method: "POST" })
        .then(pair => {
          this.set("privateKey", pair.private_key);
          this.set("publicKey", pair.public_key);
        })
        .catch(popupAjaxError)
        .finally(() => {
          this._keyLoading = false;
        });
    }
  },

  actions: {
    importTheme() {
      let options = {
        type: "POST"
      };

      if (this.get("local")) {
        options.processData = false;
        options.contentType = false;
        options.data = new FormData();
        options.data.append("theme", $("#file-input")[0].files[0]);
      } else {
        options.data = {
          remote: this.get("uploadUrl")
        };

        if (this.get("privateChecked")) {
          options.data.private_key = this.get("privateKey");
        }
      }

      this.set("loading", true);
      ajax(this.get("importUrl"), options)
        .then(result => {
          const theme = this.store.createRecord("theme", result.theme);
          this.get("adminCustomizeThemes").send("addTheme", theme);
          this.send("closeModal");
        })
        .then(() => {
          this.set("privateKey", null);
          this.set("publicKey", null);
        })
        .catch(popupAjaxError)
        .finally(() => this.set("loading", false));
    }
  }
});
