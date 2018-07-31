import { number } from "discourse/lib/formatter";
import loadScript from "discourse/lib/load-script";

export default Ember.Component.extend({
  classNames: ["admin-report-chart"],
  limit: 8,
  primaryColor: "rgb(0,136,204)",
  total: 0,

  willDestroyElement() {
    this._super(...arguments);

    this._resetChart();
  },

  didReceiveAttrs() {
    this._super(...arguments);

    Ember.run.schedule("afterRender", () => {
      const $chartCanvas = this.$(".chart-canvas");
      if (!$chartCanvas || !$chartCanvas.length) return;

      const context = $chartCanvas[0].getContext("2d");
      const model = this.get("model");
      const chartData = Ember.makeArray(
        model.get("chartData") || model.get("data")
      );
      const prevChartData = Ember.makeArray(
        model.get("prevChartData") || model.get("prev_data")
      );

      const labels = chartData.map(d => d.x);

      const data = {
        labels,
        datasets: [
          {
            data: chartData.map(d => Math.round(parseFloat(d.y))),
            backgroundColor: prevChartData.length
              ? "transparent"
              : "rgba(200,220,240,0.3)",
            borderColor: this.get("primaryColor")
          }
        ]
      };

      if (prevChartData.length) {
        data.datasets.push({
          data: prevChartData.map(d => Math.round(parseFloat(d.y))),
          borderColor: this.get("primaryColor"),
          borderDash: [5, 5],
          backgroundColor: "transparent",
          borderWidth: 1,
          pointRadius: 0
        });
      }

      loadScript("/javascripts/Chart.min.js").then(() => {
        this._resetChart();
        this._chart = new window.Chart(context, this._buildChartConfig(data));
      });
    });
  },

  _buildChartConfig(data) {
    return {
      type: "line",
      data,
      options: {
        tooltips: {
          callbacks: {
            title: tooltipItem =>
              moment(tooltipItem[0].xLabel, "YYYY-MM-DD").format("LL")
          }
        },
        legend: {
          display: false
        },
        responsive: true,
        maintainAspectRatio: false,
        layout: {
          padding: {
            left: 0,
            top: 0,
            right: 0,
            bottom: 0
          }
        },
        scales: {
          yAxes: [
            {
              display: true,
              ticks: { callback: label => number(label) }
            }
          ],
          xAxes: [
            {
              display: true,
              gridLines: { display: false },
              type: "time",
              time: {
                parser: "YYYY-MM-DD"
              }
            }
          ]
        }
      }
    };
  },

  _resetChart() {
    if (this._chart) {
      this._chart.destroy();
      this._chart = null;
    }
  }
});
