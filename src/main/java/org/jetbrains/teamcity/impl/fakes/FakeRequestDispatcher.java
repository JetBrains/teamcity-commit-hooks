

package org.jetbrains.teamcity.impl.fakes;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

public class FakeRequestDispatcher implements RequestDispatcher {

  @Override
  public void forward(final ServletRequest request, final ServletResponse response) {
  }

  @Override
  public void include(final ServletRequest request, final ServletResponse response) {
  }
}