import DiscourseURL from "discourse/lib/url";
import computed from "ember-addons/ember-computed-decorators";

export default Ember.Mixin.create({
  queryParams: ["period"],

  period: "monthly",

  availablePeriods: ["yearly", "quarterly", "monthly", "weekly"],

  @computed("period")
  startDate(period) {
    let fullDay = moment()
      .locale("en")
      .utc()
      .subtract(1, "day");

    switch (period) {
      case "yearly":
        return fullDay.subtract(1, "year").startOf("day");
        break;
      case "quarterly":
        return fullDay.subtract(3, "month").startOf("day");
        break;
      case "weekly":
        return fullDay.subtract(1, "week").startOf("day");
        break;
      case "monthly":
        return fullDay.subtract(1, "month").startOf("day");
        break;
      default:
        return fullDay.subtract(1, "month").startOf("day");
    }
  },

  @computed()
  lastWeek() {
    return moment()
      .locale("en")
      .utc()
      .endOf("day")
      .subtract(1, "week");
  },

  @computed()
  endDate() {
    return moment()
      .locale("en")
      .utc()
      .subtract(1, "day")
      .endOf("day");
  },

  actions: {
    changePeriod(period) {
      DiscourseURL.routeTo(this._reportsForPeriodURL(period));
    }
  }
});
