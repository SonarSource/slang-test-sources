# frozen_string_literal: true

require_relative "../helper"

module Arel
  module Attributes
    class MathTest < Arel::Spec
      %i[* /].each do |math_operator|
        it "average should be compatiable with #{math_operator}" do
          table = Arel::Table.new :users
          (table[:id].average.public_send(math_operator, 2)).to_sql.must_be_like %{
            AVG("users"."id") #{math_operator} 2
          }
        end

        it "count should be compatiable with #{math_operator}" do
          table = Arel::Table.new :users
          (table[:id].count.public_send(math_operator, 2)).to_sql.must_be_like %{
            COUNT("users"."id") #{math_operator} 2
          }
        end

        it "maximum should be compatiable with #{math_operator}" do
          table = Arel::Table.new :users
          (table[:id].maximum.public_send(math_operator, 2)).to_sql.must_be_like %{
            MAX("users"."id") #{math_operator} 2
          }
        end

        it "minimum should be compatiable with #{math_operator}" do
          table = Arel::Table.new :users
          (table[:id].minimum.public_send(math_operator, 2)).to_sql.must_be_like %{
            MIN("users"."id") #{math_operator} 2
          }
        end

        it "attribute node should be compatiable with #{math_operator}" do
          table = Arel::Table.new :users
          (table[:id].public_send(math_operator, 2)).to_sql.must_be_like %{
            "users"."id" #{math_operator} 2
          }
        end
      end

      %i[+ - & | ^ << >>].each do |math_operator|
        it "average should be compatiable with #{math_operator}" do
          table = Arel::Table.new :users
          (table[:id].average.public_send(math_operator, 2)).to_sql.must_be_like %{
            (AVG("users"."id") #{math_operator} 2)
          }
        end

        it "count should be compatiable with #{math_operator}" do
          table = Arel::Table.new :users
          (table[:id].count.public_send(math_operator, 2)).to_sql.must_be_like %{
            (COUNT("users"."id") #{math_operator} 2)
          }
        end

        it "maximum should be compatiable with #{math_operator}" do
          table = Arel::Table.new :users
          (table[:id].maximum.public_send(math_operator, 2)).to_sql.must_be_like %{
            (MAX("users"."id") #{math_operator} 2)
          }
        end

        it "minimum should be compatiable with #{math_operator}" do
          table = Arel::Table.new :users
          (table[:id].minimum.public_send(math_operator, 2)).to_sql.must_be_like %{
            (MIN("users"."id") #{math_operator} 2)
          }
        end

        it "attribute node should be compatiable with #{math_operator}" do
          table = Arel::Table.new :users
          (table[:id].public_send(math_operator, 2)).to_sql.must_be_like %{
            ("users"."id" #{math_operator} 2)
          }
        end
      end
    end
  end
end
