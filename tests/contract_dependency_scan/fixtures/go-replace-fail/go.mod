module example.com/go-replace-fail

go 1.22

require github.com/spark-harness/idl-go-repo/user/v1 v1.8.0

replace github.com/spark-harness/idl-go-repo/user/v1 => ../local-idl-go-repo/user/v1
